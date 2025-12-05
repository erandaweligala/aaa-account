package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduler service that terminates idle sessions based on configurable timeout threshold.
 * Sessions are considered idle if the time difference between sessionInitiatedTime and
 * current time exceeds the configured threshold.
 */
@ApplicationScoped
public class IdleSessionTerminatorScheduler {
  // todo hondle 10M users and any without overhead improve this code
    private static final Logger log = Logger.getLogger(IdleSessionTerminatorScheduler.class);

    private final CacheClient cacheClient;
    private final IdleSessionConfig config;

    @Inject
    public IdleSessionTerminatorScheduler(CacheClient cacheClient, IdleSessionConfig config) {
        this.cacheClient = cacheClient;
        this.config = config;
    }

    /**
     * Scheduled task to scan and terminate idle sessions.
     * Runs at configurable intervals defined by idle-session.scheduler-interval.
     */
    @Scheduled(every = "${idle-session.scheduler-interval:5m}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void terminateIdleSessions() {
        if (!config.enabled()) {
            log.debug("Idle session terminator is disabled, skipping execution");
            return;
        }

        long startTime = System.currentTimeMillis();
        int timeoutMinutes = config.timeoutMinutes();
        int batchSize = config.batchSize();

        log.infof("Starting idle session termination scan with timeout threshold: %d minutes", timeoutMinutes);

        AtomicInteger totalSessionsTerminated = new AtomicInteger(0);
        AtomicInteger totalUsersProcessed = new AtomicInteger(0);

        cacheClient.scanAllUserKeys()
                .group().intoLists().of(batchSize)
                .onItem().transformToUniAndMerge(userIdBatch ->
                    processUserBatch(userIdBatch, timeoutMinutes, totalSessionsTerminated, totalUsersProcessed)
                )
                .collect().asList()
                .subscribe().with(
                    result -> log.infof("Idle session termination completed. Users processed: %d, Sessions terminated: %d, Duration: %d ms",
                            totalUsersProcessed.get(), totalSessionsTerminated.get(), System.currentTimeMillis() - startTime),
                    error -> log.errorf(error, "Error during idle session termination scan")
                );
    }

    /**
     * Process a batch of users to check for idle sessions.
     */
    private Uni<Void> processUserBatch(List<String> userIds, int timeoutMinutes,
                                        AtomicInteger totalSessionsTerminated,
                                        AtomicInteger totalUsersProcessed) {
        return cacheClient.getUserDataBatch(userIds)
                .onItem().transformToUniAndMerge(userSessionData ->
                    processUserSessions(userSessionData, timeoutMinutes, totalSessionsTerminated)
                )
                .collect().asList()
                .invoke(() -> totalUsersProcessed.addAndGet(userIds.size()))
                .replaceWithVoid();
    }

    /**
     * Process sessions for a single user, terminating idle ones.
     */
    private Uni<Void> processUserSessions(UserSessionData userSessionData, int timeoutMinutes,
                                          AtomicInteger totalSessionsTerminated) {
        if (userSessionData == null || userSessionData.getSessions() == null ||
            userSessionData.getSessions().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String userName = userSessionData.getUserName();
        List<Session> sessions = userSessionData.getSessions();
        LocalDateTime now = LocalDateTime.now();

        List<Session> idleSessions = findIdleSessions(sessions, now, timeoutMinutes);

        if (idleSessions.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        log.infof("Found %d idle sessions for user: %s", idleSessions.size(), userName);

        // Remove idle sessions from the user's session list
        List<Session> activeSessions = new ArrayList<>(sessions);
        activeSessions.removeAll(idleSessions);
        userSessionData.setSessions(activeSessions);

        // Log terminated sessions
        for (Session session : idleSessions) {
            Duration idleDuration = Duration.between(session.getSessionInitiatedTime(), now);
            log.infof("Terminating idle session [sessionId: %s, user: %s, sessionInitiatedTime: %s, idleDuration: %d minutes]",
                    session.getSessionId(), userName, session.getSessionInitiatedTime(), idleDuration.toMinutes());
        }

        totalSessionsTerminated.addAndGet(idleSessions.size());

        // Update cache with remaining active sessions
        return cacheClient.updateUserAndRelatedCaches(userName, userSessionData)
                .onFailure().invoke(error ->
                    log.errorf(error, "Failed to update cache after terminating idle sessions for user: %s", userName)
                )
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Find sessions that have exceeded the idle timeout threshold.
     * A session is considered idle if (currentTime - sessionInitiatedTime) > timeoutMinutes.
     */
    private List<Session> findIdleSessions(List<Session> sessions, LocalDateTime now, int timeoutMinutes) {
        List<Session> idleSessions = new ArrayList<>();

        for (Session session : sessions) {
            if (session.getSessionInitiatedTime() == null) {
                log.warnf("Session %s has null sessionInitiatedTime, skipping", session.getSessionId());
                continue;
            }

            Duration sessionDuration = Duration.between(session.getSessionInitiatedTime(), now);
            long sessionMinutes = sessionDuration.toMinutes();

            if (sessionMinutes >= timeoutMinutes) {
                idleSessions.add(session);
                if (log.isDebugEnabled()) {
                    log.debugf("Session %s identified as idle (duration: %d minutes, threshold: %d minutes)",
                            session.getSessionId(), sessionMinutes, timeoutMinutes);
                }
            }
        }

        return idleSessions;
    }

}
