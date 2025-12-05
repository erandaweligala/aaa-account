package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex.SessionExpiryEntry;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Optimized scheduler service that terminates idle sessions based on configurable timeout threshold.
 *
 * <h2>Scaling Architecture for 10M+ Users</h2>
 *
 * <p><b>Previous approach (O(N) - not scalable):</b></p>
 * <ul>
 *   <li>SCAN all 10M user keys every 5 minutes</li>
 *   <li>Individual GET for each user to check sessions</li>
 *   <li>Linear time complexity regardless of expired session count</li>
 * </ul>
 *
 * <p><b>Optimized approach (O(log N + K) - highly scalable):</b></p>
 * <ul>
 *   <li>Use Redis Sorted Set as expiry index with score = expiry timestamp</li>
 *   <li>ZRANGEBYSCORE to get only expired sessions - O(log N + K) where K = expired count</li>
 *   <li>MGET to batch fetch user data - single network round trip</li>
 *   <li>Process only users with expired sessions, not all 10M users</li>
 * </ul>
 *
 * <p><b>Performance comparison at 10M users:</b></p>
 * <table border="1">
 *   <tr><th>Metric</th><th>Old Approach</th><th>New Approach</th></tr>
 *   <tr><td>Keys scanned</td><td>10,000,000</td><td>~1,000 (expired only)</td></tr>
 *   <tr><td>Network round trips</td><td>100,000+ (batched GETs)</td><td>2-10 (ZRANGEBYSCORE + MGET batches)</td></tr>
 *   <tr><td>Time complexity</td><td>O(N)</td><td>O(log N + K)</td></tr>
 *   <tr><td>Memory pressure</td><td>High (buffering all keys)</td><td>Low (only expired sessions)</td></tr>
 * </table>
 *
 * <p><b>Requirements for full optimization:</b></p>
 * <ul>
 *   <li>Sessions must be registered in SessionExpiryIndex when created</li>
 *   <li>Sessions must be updated in index on activity</li>
 *   <li>Sessions must be removed from index on termination</li>
 * </ul>
 *
 * @see SessionExpiryIndex for the sorted set index implementation
 */
@ApplicationScoped
public class IdleSessionTerminatorScheduler {

    private static final Logger log = Logger.getLogger(IdleSessionTerminatorScheduler.class);

    private final CacheClient cacheClient;
    private final SessionExpiryIndex sessionExpiryIndex;
    private final IdleSessionConfig config;

    @Inject
    public IdleSessionTerminatorScheduler(CacheClient cacheClient,
                                           SessionExpiryIndex sessionExpiryIndex,
                                           IdleSessionConfig config) {
        this.cacheClient = cacheClient;
        this.sessionExpiryIndex = sessionExpiryIndex;
        this.config = config;
    }

    /**
     * Scheduled task to terminate idle sessions using optimized index-based lookup.
     * Runs at configurable intervals defined by idle-session.scheduler-interval.
     *
     * <p>This method uses the SessionExpiryIndex to find expired sessions in O(log N + K)
     * time instead of scanning all users in O(N) time.</p>
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

        // Calculate expiry threshold - sessions created before this time are expired
        long expiryThresholdMillis = Instant.now()
                .minus(Duration.ofMinutes(timeoutMinutes))
                .toEpochMilli();

        log.infof("Starting optimized idle session termination with timeout: %d minutes, threshold: %d",
                timeoutMinutes, expiryThresholdMillis);

        // First, log index stats for monitoring
        logIndexStats(expiryThresholdMillis);

        AtomicInteger totalSessionsTerminated = new AtomicInteger(0);
        AtomicInteger totalUsersProcessed = new AtomicInteger(0);
        AtomicInteger totalBatchesProcessed = new AtomicInteger(0);

        // Process expired sessions in batches using the optimized index
        processExpiredSessionsBatched(expiryThresholdMillis, batchSize,
                totalSessionsTerminated, totalUsersProcessed, totalBatchesProcessed)
                .subscribe().with(
                        result -> log.infof(
                                "Idle session termination completed. " +
                                "Batches: %d, Users: %d, Sessions terminated: %d, Duration: %d ms",
                                totalBatchesProcessed.get(),
                                totalUsersProcessed.get(),
                                totalSessionsTerminated.get(),
                                System.currentTimeMillis() - startTime),
                        error -> log.errorf(error, "Error during idle session termination")
                );
    }

    /**
     * Process expired sessions in batches until no more expired sessions exist.
     * Uses recursive batch processing to handle large numbers of expired sessions.
     */
    private Uni<Void> processExpiredSessionsBatched(long expiryThresholdMillis, int batchSize,
                                                     AtomicInteger totalSessionsTerminated,
                                                     AtomicInteger totalUsersProcessed,
                                                     AtomicInteger totalBatchesProcessed) {
        return processOneBatch(expiryThresholdMillis, batchSize,
                totalSessionsTerminated, totalUsersProcessed, totalBatchesProcessed)
                .onItem().transformToUni(processedCount -> {
                    if (processedCount > 0 && processedCount >= batchSize) {
                        // More sessions may exist, process next batch
                        log.debugf("Batch complete with %d sessions, checking for more", processedCount);
                        return processExpiredSessionsBatched(expiryThresholdMillis, batchSize,
                                totalSessionsTerminated, totalUsersProcessed, totalBatchesProcessed);
                    }
                    // No more sessions or last batch was partial
                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * Process a single batch of expired sessions.
     * Returns the number of sessions processed in this batch.
     */
    private Uni<Integer> processOneBatch(long expiryThresholdMillis, int batchSize,
                                          AtomicInteger totalSessionsTerminated,
                                          AtomicInteger totalUsersProcessed,
                                          AtomicInteger totalBatchesProcessed) {

        // Query expired sessions from index - O(log N + K) complexity
        return sessionExpiryIndex.getExpiredSessions(expiryThresholdMillis, batchSize)
                .collect().asList()
                .onItem().transformToUni(expiredEntries -> {
                    if (expiredEntries.isEmpty()) {
                        log.debug("No expired sessions found in this batch");
                        return Uni.createFrom().item(0);
                    }

                    totalBatchesProcessed.incrementAndGet();
                    log.infof("Processing batch of %d expired sessions", expiredEntries.size());

                    // Group by userId for efficient batch processing
                    Map<String, List<SessionExpiryEntry>> sessionsByUser = expiredEntries.stream()
                            .collect(Collectors.groupingBy(SessionExpiryEntry::userId));

                    List<String> userIds = new ArrayList<>(sessionsByUser.keySet());
                    totalUsersProcessed.addAndGet(userIds.size());

                    // Fetch user data using MGET - single network round trip
                    return cacheClient.getUserDataBatchAsMap(userIds)
                            .onItem().transformToUni(userDataMap ->
                                processUsersAndCleanupIndex(userDataMap, sessionsByUser,
                                        expiredEntries, totalSessionsTerminated)
                            )
                            .onItem().transform(v -> expiredEntries.size());
                });
    }

    /**
     * Process users with expired sessions and clean up the index.
     */
    private Uni<Void> processUsersAndCleanupIndex(Map<String, UserSessionData> userDataMap,
                                                   Map<String, List<SessionExpiryEntry>> sessionsByUser,
                                                   List<SessionExpiryEntry> allExpiredEntries,
                                                   AtomicInteger totalSessionsTerminated) {

        List<Uni<Void>> userUpdates = new ArrayList<>();
        List<String> membersToRemove = new ArrayList<>();

        for (Map.Entry<String, List<SessionExpiryEntry>> entry : sessionsByUser.entrySet()) {
            String userId = entry.getKey();
            List<SessionExpiryEntry> expiredSessions = entry.getValue();
            UserSessionData userData = userDataMap.get(userId);

            // Collect members to remove from index
            for (SessionExpiryEntry sessionEntry : expiredSessions) {
                membersToRemove.add(sessionEntry.rawMember());
            }

            if (userData == null) {
                // User data not found in cache, just log and continue
                // The index cleanup will remove stale entries
                log.debugf("User data not found for userId: %s, will clean up index entries", userId);
                continue;
            }

            // Process the user's sessions
            Uni<Void> userUpdate = processUserExpiredSessions(userData, expiredSessions, totalSessionsTerminated);
            userUpdates.add(userUpdate);
        }

        // Remove processed entries from index in batch
        Uni<Integer> indexCleanup = sessionExpiryIndex.removeSessions(membersToRemove)
                .onItem().invoke(removed ->
                        log.debugf("Removed %d entries from session expiry index", removed));

        // Execute all user updates and index cleanup in parallel
        if (userUpdates.isEmpty()) {
            return indexCleanup.replaceWithVoid();
        }

        return Uni.join().all(userUpdates).andCollectFailures()
                .onItem().transformToUni(results -> indexCleanup.replaceWithVoid())
                .onFailure().invoke(e -> log.errorf(e, "Error processing user updates"));
    }

    /**
     * Process expired sessions for a single user.
     */
    private Uni<Void> processUserExpiredSessions(UserSessionData userData,
                                                  List<SessionExpiryEntry> expiredSessionEntries,
                                                  AtomicInteger totalSessionsTerminated) {
        if (userData.getSessions() == null || userData.getSessions().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String userName = userData.getUserName();
        List<Session> sessions = userData.getSessions();

        // Build set of expired session IDs for O(1) lookup
        var expiredSessionIds = expiredSessionEntries.stream()
                .map(SessionExpiryEntry::sessionId)
                .collect(Collectors.toSet());

        // Find sessions to terminate
        List<Session> sessionsToTerminate = sessions.stream()
                .filter(s -> expiredSessionIds.contains(s.getSessionId()))
                .toList();

        if (sessionsToTerminate.isEmpty()) {
            // Sessions may have been terminated by other means
            log.debugf("No matching sessions found for user %s, may have been terminated already", userName);
            return Uni.createFrom().voidItem();
        }

        log.infof("Terminating %d idle sessions for user: %s", sessionsToTerminate.size(), userName);

        // Log session details
        LocalDateTime now = LocalDateTime.now();
        for (Session session : sessionsToTerminate) {
            if (session.getSessionInitiatedTime() != null) {
                Duration idleDuration = Duration.between(session.getSessionInitiatedTime(), now);
                log.infof("Terminating idle session [sessionId: %s, user: %s, idleDuration: %d minutes]",
                        session.getSessionId(), userName, idleDuration.toMinutes());
            }
        }

        // Remove terminated sessions
        List<Session> activeSessions = new ArrayList<>(sessions);
        activeSessions.removeAll(sessionsToTerminate);
        userData.setSessions(activeSessions);

        totalSessionsTerminated.addAndGet(sessionsToTerminate.size());

        // Update cache
        return cacheClient.updateUserAndRelatedCaches(userName, userData)
                .onFailure().invoke(error ->
                        log.errorf(error, "Failed to update cache for user: %s", userName))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Log index statistics for monitoring.
     */
    private void logIndexStats(long expiryThresholdMillis) {
        sessionExpiryIndex.getTotalIndexedSessions()
                .onItem().invoke(total ->
                        log.infof("Session expiry index stats - Total indexed: %d", total))
                .subscribe().with(
                        v -> {},
                        e -> log.warnf("Failed to get index stats: %s", e.getMessage())
                );

        sessionExpiryIndex.getExpiredSessionCount(expiryThresholdMillis)
                .onItem().invoke(expired ->
                        log.infof("Session expiry index stats - Expired sessions: %d", expired))
                .subscribe().with(
                        v -> {},
                        e -> log.warnf("Failed to get expired count: %s", e.getMessage())
                );
    }

    // =========================================================================
    // FALLBACK: Legacy full-scan method for migration period
    // =========================================================================

    /**
     * Fallback method using full scan for migration period.
     * This is used when the session expiry index is not yet populated.
     *
     * @deprecated Use the optimized index-based approach instead.
     *             This method will be removed after migration.
     */
    @Deprecated
    public void terminateIdleSessionsLegacy() {
        if (!config.enabled()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        int timeoutMinutes = config.timeoutMinutes();
        int batchSize = config.batchSize();

        log.warn("Using legacy full-scan idle session termination - consider migrating to index-based approach");

        AtomicInteger totalSessionsTerminated = new AtomicInteger(0);
        AtomicInteger totalUsersProcessed = new AtomicInteger(0);

        cacheClient.scanAllUserKeys()
                .group().intoLists().of(batchSize)
                .onItem().transformToUniAndMerge(userIdBatch ->
                        processLegacyUserBatch(userIdBatch, timeoutMinutes, totalSessionsTerminated, totalUsersProcessed)
                )
                .collect().asList()
                .subscribe().with(
                        result -> log.infof("Legacy termination completed. Users: %d, Sessions: %d, Duration: %d ms",
                                totalUsersProcessed.get(), totalSessionsTerminated.get(),
                                System.currentTimeMillis() - startTime),
                        error -> log.errorf(error, "Error during legacy idle session termination")
                );
    }

    private Uni<Void> processLegacyUserBatch(List<String> userIds, int timeoutMinutes,
                                              AtomicInteger totalSessionsTerminated,
                                              AtomicInteger totalUsersProcessed) {
        return cacheClient.getUserDataBatch(userIds)
                .onItem().transformToUniAndMerge(userData ->
                        processLegacyUserSessions(userData, timeoutMinutes, totalSessionsTerminated))
                .collect().asList()
                .invoke(() -> totalUsersProcessed.addAndGet(userIds.size()))
                .replaceWithVoid();
    }

    private Uni<Void> processLegacyUserSessions(UserSessionData userData, int timeoutMinutes,
                                                 AtomicInteger totalSessionsTerminated) {
        if (userData == null || userData.getSessions() == null || userData.getSessions().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String userName = userData.getUserName();
        List<Session> sessions = userData.getSessions();
        LocalDateTime now = LocalDateTime.now();

        List<Session> idleSessions = findIdleSessions(sessions, now, timeoutMinutes);

        if (idleSessions.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<Session> activeSessions = new ArrayList<>(sessions);
        activeSessions.removeAll(idleSessions);
        userData.setSessions(activeSessions);

        totalSessionsTerminated.addAndGet(idleSessions.size());

        return cacheClient.updateUserAndRelatedCaches(userName, userData)
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private List<Session> findIdleSessions(List<Session> sessions, LocalDateTime now, int timeoutMinutes) {
        List<Session> idleSessions = new ArrayList<>();
        for (Session session : sessions) {
            if (session.getSessionInitiatedTime() == null) {
                continue;
            }
            Duration sessionDuration = Duration.between(session.getSessionInitiatedTime(), now);
            if (sessionDuration.toMinutes() >= timeoutMinutes) {
                idleSessions.add(session);
            }
        }
        return idleSessions;
    }
}
