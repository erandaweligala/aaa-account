package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.model.coa.CoADisconnectRequest;
import com.csg.airtel.aaa4j.domain.model.coa.CoADisconnectResponse;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.clients.CoAHttpClient;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


@ApplicationScoped
public class COAService {
    private static final Logger log = Logger.getLogger(COAService.class);

    private final AccountProducer accountProducer;
    private final MonitoringService monitoringService;
    private final CoAHttpClient coaHttpClient;
    private final CacheClient cacheClient;

    public COAService(AccountProducer accountProducer,
                      MonitoringService monitoringService,
                      CoAHttpClient coaHttpClient,
                      CacheClient cacheClient) {
        this.accountProducer = accountProducer;
        this.monitoringService = monitoringService;
        this.coaHttpClient = coaHttpClient;
        this.cacheClient = cacheClient;
    }

    public Uni<Void> clearAllSessionsAndSendCOA(UserSessionData userSessionData, String username,String sessionId) {
        List<Session> sessions = userSessionData.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        if(sessionId != null){
            sessions = sessions.stream().filter(rs -> Objects.equals(rs.getSessionId(), sessionId))
                    .toList();
        }

        // Use merge instead of concatenate for parallel execution (better throughput)
        return Multi.createFrom().iterable(sessions)
                .onItem().transformToUni(session ->
                        accountProducer.produceAccountingResponseEvent(
                                        MappingUtil.createResponse(
                                                session.getSessionId(),
                                                AppConstant.DISCONNECT_ACTION,
                                                session.getNasIp(),
                                                session.getFramedId(),
                                                session.getUserName() !=null ?session.getUserName():username
                                        )
                                )
                                .invoke(() -> {
                                    // Record COA request metric
                                    monitoringService.recordCOARequest();
                                    generateAndSendCoaDisconnectCDR(session, username);
                                })
                                .onFailure().retry()
                                .withBackOff(Duration.ofMillis(AppConstant.COA_RETRY_INITIAL_BACKOFF_MS), Duration.ofSeconds(AppConstant.COA_RETRY_MAX_BACKOFF_SECONDS))
                                .atMost(AppConstant.COA_RETRY_MAX_ATTEMPTS)
                                .onFailure().invoke(failure -> {
                                    if (log.isDebugEnabled()) {
                                        log.debugf(failure, "Failed to produce disconnect event for session: %s",
                                                session.getSessionId());
                                    }
                                })
                                .onFailure().recoverWithNull()
                )
                .merge() // Parallel execution instead of sequential
                .collect().asList()
                .ifNoItem().after(Duration.ofSeconds(AppConstant.COA_TIMEOUT_SECONDS)).fail()
                .replaceWithVoid();
    }



    /**
     * Generate and send COA Disconnect CDR event asynchronously.
     * This method builds a CDR event for a COA disconnect operation and sends it to the accounting system.
     *
     * @param session the session being disconnected
     * @param username the username associated with the session
     */
    private void generateAndSendCoaDisconnectCDR(Session session, String username) {
        try {
            AccountingCDREvent cdrEvent = CdrMappingUtil.buildCoaDisconnectCDREvent(session, username);
            accountProducer.produceAccountingCDREvent(cdrEvent)
                    .subscribe()
                    .with(
                            success -> log.infof("COA Disconnect CDR event sent successfully for session: %s, user: %s",
                                    session.getSessionId(), username),
                            failure -> log.errorf(failure, "Failed to send COA Disconnect CDR event for session: %s, user: %s",
                                    session.getSessionId(), username)
                    );
        } catch (Exception e) {
            log.errorf(e, "Error building COA Disconnect CDR event for session: %s, user: %s",
                    session.getSessionId(), username);
        }
    }

    /**
     * Produce accounting response event and generate COA disconnect CDR.
     * This method sends an accounting response event and then generates a COA disconnect CDR for the session.
     *
     * @param event the accounting response event to send
     * @param session the session being disconnected
     * @param username the username associated with the session
     * @return Uni that completes when the event is sent
     */


    public Uni<Void> produceAccountingResponseEvent(AccountingResponseEvent event, Session session, String username) {
        return coaHttpClient.sendDisconnect(event)
                .onItem().transformToUni(response -> {
                    if (response.isAck()) {
                        log.infof("CoA disconnect ACK received for session: %s, clearing cache", session.getSessionId());
                        // Record COA request metric
                        monitoringService.recordCOARequest();
                        // Generate and send CDR
                        generateAndSendCoaDisconnectCDR(session, username);
                        // Clear session from cache after ACK
                        return clearSessionFromCache(username, session.getSessionId());
                    } else {
                        log.warnf("CoA disconnect NACK/Failed for session: %s, status: %s, message: %s",
                                session.getSessionId(), response.status(), response.message());
                        return Uni.createFrom().voidItem();
                    }
                })
                .onFailure().invoke(failure ->
                        log.errorf(failure, "HTTP CoA disconnect failed for session: %s", session.getSessionId())
                )
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Send CoA Disconnect via HTTP (non-blocking, no overhead).
     * This method sends CoA disconnect requests directly to NAS via HTTP without Kafka overhead.
     * After receiving ACK response, it clears the session from cache.
     *
     * Features:
     * - Non-blocking reactive operation
     * - No retry, circuit breaker, or fallback overhead
     * - Direct HTTP POST to NAS endpoint
     * - Automatic cache cleanup on ACK
     *
     * @param userSessionData the user session data
     * @param username the username
     * @param sessionId specific session to disconnect (null for all sessions)
     * @return Uni that completes when all disconnects are sent and cache is cleared
     */
    public Uni<Void> sendCoADisconnectViaHttp(UserSessionData userSessionData, String username, String sessionId) {
        List<Session> sessions = userSessionData.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            log.debugf("No sessions to disconnect for user: %s", username);
            return Uni.createFrom().voidItem();
        }

        // Filter sessions if specific sessionId is provided
        if (sessionId != null) {
            sessions = sessions.stream()
                    .filter(s -> Objects.equals(s.getSessionId(), sessionId))
                    .collect(Collectors.toList());
        }

        if (sessions.isEmpty()) {
            log.debugf("No matching sessions found for user: %s, sessionId: %s", username, sessionId);
            return Uni.createFrom().voidItem();
        }

        log.infof("Sending HTTP CoA disconnect for user: %s, session count: %d", username, sessions.size());

        // Send HTTP disconnect for each session in parallel (non-blocking)
        return Multi.createFrom().iterable(sessions)
                .onItem().transformToUni(session -> {

                    AccountingResponseEvent request = MappingUtil.createResponse(
                            session.getSessionId(),
                            AppConstant.DISCONNECT_ACTION,
                            session.getNasIp(),
                            session.getFramedId(),
                            session.getUserName() != null ? session.getUserName() : username);

                    // Send HTTP request (non-blocking)
                    return coaHttpClient.sendDisconnect(request)
                            .onItem().transformToUni(response -> {
                                if (response.isAck()) {
                                    log.infof("CoA disconnect ACK received for session: %s, clearing cache", session.getSessionId());
                                    // Record metric
                                    monitoringService.recordCOARequest();
                                    // Generate and send CDR
                                    generateAndSendCoaDisconnectCDR(session, username);
                                    // Clear session from cache after ACK
                                    return clearSessionFromCache(username, session.getSessionId());
                                } else {
                                    log.warnf("CoA disconnect NACK/Failed for session: %s, status: %s, message: %s",
                                            session.getSessionId(), response.status(), response.message());
                                    return Uni.createFrom().voidItem();
                                }
                            })
                            .onFailure().invoke(failure ->
                                    log.errorf(failure, "HTTP CoA disconnect failed for session: %s", session.getSessionId())
                            )
                            .onFailure().recoverWithNull(); // Continue with other sessions on failure
                })
                .merge() // Parallel execution for all sessions
                .collect().asList()
                .replaceWithVoid();
    }

    /**
     * Clear specific session from cache.
     * Removes the session from user's session list and updates cache.
     *
     * @param username the username
     * @param sessionId the session ID to clear
     * @return Uni that completes when cache is updated
     */
    private Uni<Void> clearSessionFromCache(String username, String sessionId) {
        return cacheClient.getUserData(username)
                .onItem().transformToUni(userData -> {
                    if (userData == null || userData.getSessions() == null) {
                        log.debugf("No user data found for username: %s", username);
                        return Uni.createFrom().voidItem();
                    }

                    // Remove session from list
                    List<Session> updatedSessions = userData.getSessions().stream()
                            .filter(s -> !Objects.equals(s.getSessionId(), sessionId))
                            .collect(Collectors.toList());

                    // Update user data with modified sessions
                    UserSessionData updatedUserData = userData.toBuilder()
                            .sessions(updatedSessions)
                            .build();

                    log.infof("Clearing session from cache: user=%s, sessionId=%s, remaining sessions=%d",
                            username, sessionId, updatedSessions.size());

                    // Update cache
                    return cacheClient.updateUserAndRelatedCaches(username, updatedUserData)
                            .replaceWithVoid();
                })
                .onFailure().invoke(failure ->
                        log.errorf(failure, "Failed to clear session from cache: user=%s, sessionId=%s",
                                username, sessionId)
                )
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

}
