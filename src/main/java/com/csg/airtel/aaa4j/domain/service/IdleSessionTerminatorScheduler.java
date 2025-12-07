package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.EventType;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Optimized scheduler service that terminates idle sessions based on configurable timeout threshold.
*/

@ApplicationScoped
public class IdleSessionTerminatorScheduler {

    private static final Logger log = Logger.getLogger(IdleSessionTerminatorScheduler.class);

    private final CacheClient cacheClient;
    private final SessionExpiryIndex sessionExpiryIndex;
    private final IdleSessionConfig config;
    private final AccountProducer accountProducer;

    @Inject
    public IdleSessionTerminatorScheduler(CacheClient cacheClient,
                                           SessionExpiryIndex sessionExpiryIndex,
                                           IdleSessionConfig config,
                                           AccountProducer accountProducer) {
        this.cacheClient = cacheClient;
        this.sessionExpiryIndex = sessionExpiryIndex;
        this.config = config;
        this.accountProducer = accountProducer;
    }

    /**
     * Scheduled task to terminate idle sessions using optimized index-based lookup.
     * Runs at configurable intervals defined by idle-session.scheduler-interval.
     * After session termination, creates DBWrite requests and publishes DB write events
     * to persist balance updates to the database.
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
                                         totalSessionsTerminated)
                            )
                            .onItem().transform(v -> expiredEntries.size());
                });
    }

    /**
     * Process users with expired sessions and clean up the index.
     */
    private Uni<Void> processUsersAndCleanupIndex(Map<String, UserSessionData> userDataMap,
                                                   Map<String, List<SessionExpiryEntry>> sessionsByUser,
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
     * Terminates sessions, publishes DBWrite events for balance updates, and updates cache.
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

        // Remove terminated sessions
        List<Session> activeSessions = new ArrayList<>(sessions);
        activeSessions.removeAll(sessionsToTerminate);
        userData.setSessions(activeSessions);

        totalSessionsTerminated.addAndGet(sessionsToTerminate.size());

        // Create and publish DBWrite events for each terminated session's balances
        List<Uni<Void>> dbWriteEvents = createDBWriteEventsForTerminatedSessions(
                userData, sessionsToTerminate, userName);

        // Execute DBWrite events in parallel, then update cache
        Uni<Void> dbWriteUni = dbWriteEvents.isEmpty()
                ? Uni.createFrom().voidItem()
                : Uni.join().all(dbWriteEvents).andCollectFailures()
                        .onFailure().invoke(error ->
                                log.errorf(error, "Failed to publish DBWrite events for user: %s", userName))
                        .onFailure().recoverWithNull()
                        .replaceWithVoid();

        // Update cache after publishing DBWrite events
        return dbWriteUni.chain(() -> cacheClient.updateUserAndRelatedCaches(userName, userData)
                .onFailure().invoke(error ->
                        log.errorf(error, "Failed to update cache for user: %s", userName))
                .onFailure().recoverWithNull()
                .replaceWithVoid());
    }

    /**
     * Creates DBWrite events for terminated sessions.
     * For each terminated session, creates a DBWriteRequest for each balance and publishes it.
     *
     * @param userData the user session data containing balances
     * @param sessionsToTerminate the list of sessions being terminated
     * @param userName the username
     * @return list of Uni representing DBWrite event publish operations
     */
    private List<Uni<Void>> createDBWriteEventsForTerminatedSessions(
            UserSessionData userData,
            List<Session> sessionsToTerminate,
            String userName) {

        List<Uni<Void>> dbWriteEvents = new ArrayList<>();
        List<Balance> balances = userData.getBalance();

        if (balances == null || balances.isEmpty()) {
            log.debugf("No balances found for user %s, skipping DBWrite events", userName);
            return dbWriteEvents;
        }

        for (Session session : sessionsToTerminate) {
            String sessionId = session.getSessionId();

            for (Balance balance : balances) {
                DBWriteRequest dbWriteRequest = MappingUtil.createDBWriteRequest(
                        balance, userName, sessionId, EventType.UPDATE_EVENT);

                Uni<Void> dbWriteUni = accountProducer.produceDBWriteEvent(dbWriteRequest)
                        .onFailure().invoke(throwable ->
                                log.errorf(throwable, "Failed to produce DBWrite event for session: %s, balance: %s",
                                        sessionId, balance.getBucketId()))
                        .onFailure().recoverWithNull()
                        .replaceWithVoid();

                dbWriteEvents.add(dbWriteUni);
                log.debugf("Created DBWrite event for session: %s, balance: %s", sessionId, balance.getBucketId());
            }
        }

        log.infof("Created %d DBWrite events for user: %s", dbWriteEvents.size(), userName);
        return dbWriteEvents;
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

}
