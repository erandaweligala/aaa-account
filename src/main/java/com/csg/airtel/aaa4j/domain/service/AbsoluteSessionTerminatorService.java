package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.EventType;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminates sessions that have reached their absolute timeout.
 *
 * <p>Called by {@link com.csg.airtel.aaa4j.application.listener.SessionKeyspaceListener}
 * when the Redis TTL key ({@code session:ttl:{userId}:{sessionId}}) expires.</p>
 *
 * <p>On termination:</p>
 * <ol>
 *   <li>Fetches user session data from cache</li>
 *   <li>Removes the expired session from the session list</li>
 *   <li>Removes the session from the sorted-set expiry index</li>
 *   <li>Triggers a DB write event to persist the balance state</li>
 *   <li>Updates the cache with the modified user data</li>
 * </ol>
 */
@ApplicationScoped
public class AbsoluteSessionTerminatorService {

    private static final Logger log = Logger.getLogger(AbsoluteSessionTerminatorService.class);
    private static final String M_TERMINATE = "terminateAbsoluteSession";

    private final CacheClient cacheClient;
    private final SessionExpiryIndex sessionExpiryIndex;
    private final AccountProducer accountProducer;
    private final MonitoringService monitoringService;
    private final COAService coaService;

    @Inject
    public AbsoluteSessionTerminatorService(CacheClient cacheClient,
                                             SessionExpiryIndex sessionExpiryIndex,
                                             AccountProducer accountProducer,
                                             MonitoringService monitoringService,
                                             COAService coaService) {
        this.cacheClient = cacheClient;
        this.sessionExpiryIndex = sessionExpiryIndex;
        this.accountProducer = accountProducer;
        this.monitoringService = monitoringService;
        this.coaService = coaService;
    }

    /**
     * Terminates the session identified by userId + sessionId.
     * Safe to call multiple times – idempotent when the session is already gone.
     *
     * @param userId    The user ID
     * @param sessionId The session ID
     * @return Uni that completes when termination is done
     */
    public Uni<Void> terminateSession(String userId, String sessionId) {
        LoggingUtil.logInfo(log, M_TERMINATE,
                "Absolute timeout triggered: userId=%s sessionId=%s", userId, sessionId);

        return cacheClient.getUserData(userId)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        LoggingUtil.logDebug(log, M_TERMINATE,
                                "User data not found for userId=%s, cleaning up index only", userId);
                        return sessionExpiryIndex.removeSession(userId, sessionId);
                    }
                    return processTermination(userData, userId, sessionId);
                })
                .onFailure().invoke(e ->
                        LoggingUtil.logError(log, M_TERMINATE, e,
                                "Error terminating absolute timeout session: userId=%s sessionId=%s", userId, sessionId))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Uni<Void> processTermination(UserSessionData userData, String userId, String sessionId) {
        List<Session> sessions = userData.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            return sessionExpiryIndex.removeSession(userId, sessionId);
        }

        Session target = findSession(sessions, sessionId);
        if (target == null) {
            LoggingUtil.logDebug(log, M_TERMINATE,
                    "Session %s not found for user %s – already terminated", sessionId, userId);
            return sessionExpiryIndex.removeSession(userId, sessionId);
        }

        monitoringService.recordSessionTerminated();

        LoggingUtil.logInfo(log, M_TERMINATE,
                "Removing absolute timeout session %s for user %s", sessionId, userId);

        // Send CoA disconnect via HTTP; fall back to original userData if CoA itself fails
        return coaService.clearAllSessionsAndSendCOA(userData, userId, sessionId)
                .onFailure().recoverWithItem(userData)
                .onItem().transformToUni(updatedUserData -> {
                    // Ensure the terminated session is removed regardless of CoA ACK/NAK
                    List<Session> remaining = new ArrayList<>();
                    if (updatedUserData.getSessions() != null) {
                        for (Session s : updatedUserData.getSessions()) {
                            if (!sessionId.equals(s.getSessionId())) {
                                remaining.add(s);
                            }
                        }
                    }
                    updatedUserData.setSessions(remaining);

                    // DB write + index removal + cache update – execute in parallel
                    Uni<Void> dbWrite      = triggerDBWriteIfNeeded(target, updatedUserData.getBalance());
                    Uni<Void> indexRemoval = sessionExpiryIndex.removeSession(userId, sessionId);
                    Uni<Void> cacheUpdate  = cacheClient.updateUserAndRelatedCaches(
                            updatedUserData.getUserName(), updatedUserData, updatedUserData.getUserName());

                    return Uni.join().all(dbWrite, indexRemoval, cacheUpdate).andCollectFailures()
                            .replaceWithVoid()
                            .onFailure().invoke(e ->
                                    LoggingUtil.logError(log, M_TERMINATE, e,
                                            "Error during cleanup for userId=%s sessionId=%s", userId, sessionId));
                });
    }

    private Session findSession(List<Session> sessions, String sessionId) {
        for (Session s : sessions) {
            if (sessionId.equals(s.getSessionId())) {
                return s;
            }
        }
        return null;
    }

    private Uni<Void> triggerDBWriteIfNeeded(Session session, List<Balance> balances) {
        if (balances == null || balances.isEmpty() || session.getPreviousUsageBucketId() == null) {
            return Uni.createFrom().voidItem();
        }

        Balance match = null;
        for (Balance b : balances) {
            if (session.getPreviousUsageBucketId().equals(b.getBucketId())) {
                match = b;
                break;
            }
        }

        if (match == null || match.getQuota() >= session.getAvailableBalance()) {
            return Uni.createFrom().voidItem();
        }

        DBWriteRequest dbWriteRequest = MappingUtil.createDBWriteRequest(
                match,
                match.getBucketUsername(),
                session.getSessionId(),
                EventType.UPDATE_EVENT
        );

        return accountProducer.produceDBWriteEvent(dbWriteRequest)
                .onFailure().invoke(e ->
                        LoggingUtil.logError(log, M_TERMINATE, e,
                                "Failed to produce DB write event for terminated session: %s", session.getSessionId()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}
