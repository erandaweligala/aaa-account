package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
    private static final String CLASS_NAME = IdleSessionTerminatorScheduler.class.getSimpleName();

    private final CacheClient cacheClient;
    private final SessionExpiryIndex sessionExpiryIndex;
    private final IdleSessionConfig config;
    private final AccountProducer accountProducer;
    private final MonitoringService monitoringService;


    @Inject
    public IdleSessionTerminatorScheduler(CacheClient cacheClient,
                                          SessionExpiryIndex sessionExpiryIndex,
                                          IdleSessionConfig config,
                                          AccountProducer accountProducer,
                                          MonitoringService monitoringService
                                    ) {
        this.cacheClient = cacheClient;
        this.sessionExpiryIndex = sessionExpiryIndex;
        this.config = config;
        this.accountProducer = accountProducer;
        this.monitoringService = monitoringService;

    }

    /**
     * Scheduled task to terminate idle sessions using optimized index-based lookup.
     * Runs at configurable intervals defined by idle-session.scheduler-interval.
     */
    @Scheduled(every = "${idle-session.scheduler-interval:5m}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void terminateIdleSessions() {
        if (!config.enabled()) {
            LoggingUtil.logDebug(log, CLASS_NAME, "terminateIdleSessions", "Idle session terminator is disabled, skipping execution");
            return;
        }

        long startTime = System.currentTimeMillis();
        int timeoutMinutes = config.timeoutMinutes();
        int batchSize = config.batchSize();

        // Calculate expiry threshold - sessions created before this time are expired
        long expiryThresholdMillis = Instant.now()
                .minus(Duration.ofMinutes(timeoutMinutes))
                .toEpochMilli();

        LoggingUtil.logInfo(log, CLASS_NAME, "terminateIdleSessions", "Starting optimized idle session termination with timeout: %d minutes, threshold: %d",
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
                        result -> {
                            int terminatedCount = totalSessionsTerminated.get();
                            // Record idle session termination metrics
                            monitoringService.recordIdleSessionsTerminated(terminatedCount);

                            LoggingUtil.logInfo(log, CLASS_NAME, "terminateIdleSessions",
                                    "Idle session termination completed. " +
                                    "Batches: %d, Users: %d, Sessions terminated: %d, Duration: %d ms",
                                    totalBatchesProcessed.get(),
                                    totalUsersProcessed.get(),
                                    terminatedCount,
                                    System.currentTimeMillis() - startTime);
                        },
                        error -> LoggingUtil.logError(log, CLASS_NAME, "terminateIdleSessions", error, "Error during idle session termination")
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
                        LoggingUtil.logDebug(log, CLASS_NAME, "processExpiredSessionsBatched", "Batch complete with %d sessions, checking for more", processedCount);
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
                        LoggingUtil.logDebug(log, CLASS_NAME, "processOneBatch", "No expired sessions found in this batch");
                        return Uni.createFrom().item(0);
                    }

                    totalBatchesProcessed.incrementAndGet();
                    LoggingUtil.logInfo(log, CLASS_NAME, "processOneBatch", "Processing batch of %d expired sessions", expiredEntries.size());

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

                LoggingUtil.logDebug(log, CLASS_NAME, "processUsersAndCleanupIndex", "User data not found for userId: %s, will clean up index entries", userId);
                continue;
            }

            // Process the user's sessions
            Uni<Void> userUpdate = processUserExpiredSessions(userData, expiredSessions, totalSessionsTerminated);
            userUpdates.add(userUpdate);
        }

        // Remove processed entries from index in batch
        Uni<Integer> indexCleanup = sessionExpiryIndex.removeSessions(membersToRemove)
                .onItem().invoke(removed ->
                        LoggingUtil.logDebug(log, CLASS_NAME, "processUsersAndCleanupIndex", "Removed %d entries from session expiry index", removed));

        // Execute all user updates and index cleanup in parallel
        if (userUpdates.isEmpty()) {
            return indexCleanup.replaceWithVoid();
        }

        return Uni.join().all(userUpdates).andCollectFailures()
                .onItem().transformToUni(results -> indexCleanup.replaceWithVoid())
                .onFailure().invoke(e -> LoggingUtil.logError(log, CLASS_NAME, "processUsersAndCleanupIndex", e, "Error processing user updates"));
    }

    /**
     * Process expired sessions for a single user.
     * This method removes expired sessions from cache and triggers DB write operations
     * to persist balance updates for terminated sessions.
     * Also checks for absolute session timeout based on sessionInitiatedTime and sessionTimeOut.
     */
    private Uni<Void> processUserExpiredSessions(UserSessionData userData,
                                                  List<SessionExpiryEntry> expiredSessionEntries,
                                                  AtomicInteger totalSessionsTerminated) {
        if (userData.getSessions() == null || userData.getSessions().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String userName = userData.getUserName();
        List<Session> sessions = userData.getSessions();

        // Build set of expired session IDs for O(1) lookup - using efficient loop instead of stream
        int expiredCount = expiredSessionEntries.size();
        var expiredSessionIds = HashSet.newHashSet(expiredCount);
        for (SessionExpiryEntry entry : expiredSessionEntries) {
            expiredSessionIds.add(entry.sessionId());
        }

        // Find sessions to terminate - using efficient loop instead of stream
        // Check both idle timeout (from index) and absolute timeout (from sessionInitiatedTime)
        List<Session> sessionsToTerminate = new ArrayList<>();
        for (Session session : sessions) {
            boolean shouldTerminate = expiredSessionIds.contains(session.getSessionId()) ||
                    isSessionAbsoluteTimeoutExceeded(session);
            if (shouldTerminate) {
                sessionsToTerminate.add(session);
            }
        }

        if (sessionsToTerminate.isEmpty()) {
            // Sessions may have been terminated by other means
            LoggingUtil.logDebug(log, CLASS_NAME, "processUserExpiredSessions", "No matching sessions found for user %s, may have been terminated already", userName);
            return Uni.createFrom().voidItem();
        }

        LoggingUtil.logInfo(log, CLASS_NAME, "processUserExpiredSessions", "Terminating %d idle sessions for user: %s", sessionsToTerminate.size(), userName);

        // Remove terminated sessions
        List<Session> activeSessions = new ArrayList<>(sessions);
        activeSessions.removeAll(sessionsToTerminate);
        userData.setSessions(activeSessions);

        totalSessionsTerminated.addAndGet(sessionsToTerminate.size());

        // Trigger DB write operations for terminated sessions to persist balance state
        return triggerDBRequestInitiate(sessionsToTerminate, userData)
                .onItem().transformToUni(v ->
                        // Update cache after DB write is initiated
                        cacheClient.updateUserAndRelatedCaches(userName, userData,userName)
                                .onFailure().invoke(error ->
                                        LoggingUtil.logError(log, CLASS_NAME, "processUserExpiredSessions", error, "Failed to update cache for user: %s", userName))
                                .onFailure().recoverWithNull()
                                .replaceWithVoid()
                );
    }

    /**
     * Find a balance matching the given bucket ID.
     *
     * @param balances List of balances to search
     * @param bucketId The bucket ID to match
     * @return Matching balance or null if not found
     */
    private Balance findBalanceByBucketId(List<Balance> balances, String bucketId) {
        for (Balance balance : balances) {
            if (bucketId.equals(balance.getBucketId())) {
                return balance;
            }
        }
        return null;
    }

    /**
     * Create a DB write operation for a session if the balance needs to be persisted.
     *
     * @param session The session being terminated
     * @param balance The matching balance
     * @return Uni for the DB write operation, or null if no write is needed
     */
    private Uni<Void> createDBWriteOperationIfNeeded(Session session, Balance balance) {
        if (balance.getQuota() < session.getAvailableBalance()) {
            return null;
        }

        DBWriteRequest dbWriteRequest = MappingUtil.createDBWriteRequest(
                balance,
                balance.getBucketUsername(),
                session.getSessionId(),
                EventType.UPDATE_EVENT
        );

        LoggingUtil.logDebug(log, CLASS_NAME, "createDBWriteOperationIfNeeded", "Triggered DB write for terminated session: %s, bucketId: %s",
                session.getSessionId(), balance.getBucketId());

        return accountProducer.produceDBWriteEvent(dbWriteRequest)
                .onFailure().invoke(error ->
                        LoggingUtil.logError(log, CLASS_NAME, "createDBWriteOperationIfNeeded", error, "Failed to produce DB write event for session: %s",
                                session.getSessionId()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Process a single session and create a DB write operation if needed.
     *
     * @param session The session to process
     * @param balances List of balances to search for matching bucket
     * @return Uni for the DB write operation, or null if no write is needed
     */
    private Uni<Void> processSessionForDBWrite(Session session, List<Balance> balances) {
        String bucketId = session.getPreviousUsageBucketId();
        if (bucketId == null) {
            return null;
        }

        Balance matchingBalance = findBalanceByBucketId(balances, bucketId);
        if (matchingBalance == null) {
            return null;
        }

        return createDBWriteOperationIfNeeded(session, matchingBalance);
    }

    /**
     * Triggers DB write operations to persist balance state for terminated sessions.
     * Uses efficient loops to avoid stream overhead and produces events reactively.
     *
     * @param sessionsToTerminate list of sessions being terminated
     * @param userData user session data containing balance information
     * @return Uni that completes when all DB write events are produced
     */
    private Uni<Void> triggerDBRequestInitiate(List<Session> sessionsToTerminate, UserSessionData userData) {
        if (sessionsToTerminate == null || sessionsToTerminate.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<Balance> balances = userData.getBalance();
        if (balances == null || balances.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<Uni<Void>> dbWriteOperations = new ArrayList<>();

        for (Session session : sessionsToTerminate) {
            Uni<Void> dbWriteOp = processSessionForDBWrite(session, balances);
            if (dbWriteOp != null) {
                dbWriteOperations.add(dbWriteOp);
            }
        }

        if (dbWriteOperations.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Uni.join().all(dbWriteOperations).andCollectFailures()
                .replaceWithVoid()
                .onFailure().invoke(e -> LoggingUtil.logError(log, CLASS_NAME, "triggerDBRequestInitiate", e, "Error producing DB write events for terminated sessions"));
    }

    /**
     * Log index statistics for monitoring.
     */
    private void logIndexStats(long expiryThresholdMillis) {
        sessionExpiryIndex.getTotalIndexedSessions()
                .onItem().invoke(total ->
                        LoggingUtil.logInfo(log, CLASS_NAME, "logIndexStats", "Session expiry index stats - Total indexed: %d", total))
                .subscribe().with(
                        v -> {},
                        e -> LoggingUtil.logWarn(log, CLASS_NAME, "logIndexStats", "Failed to get index stats: %s", e.getMessage())
                );

        sessionExpiryIndex.getExpiredSessionCount(expiryThresholdMillis)
                .onItem().invoke(expired ->
                        LoggingUtil.logInfo(log, CLASS_NAME, "logIndexStats", "Session expiry index stats - Expired sessions: %d", expired))
                .subscribe().with(
                        v -> {},
                        e -> LoggingUtil.logWarn(log, CLASS_NAME, "logIndexStats", "Failed to get expired count: %s", e.getMessage())
                );
    }

    /**
     * Checks if a session has exceeded its absolute timeout based on sessionInitiatedTime and sessionTimeOut.
     *
     * @param session The session to check
     * @return true if the session has exceeded the absolute timeout, false otherwise
     */
    private boolean isSessionAbsoluteTimeoutExceeded(Session session) {
        if (session == null || session.getSessionInitiatedTime() == null) {
            return false;
        }

        try {
            // Parse sessionTimeOut as minutes
            long timeoutMinutes = Long.parseLong(session.getAbsoluteTimeOut().trim());

            // Calculate when the session should expire (sessionInitiatedTime + timeoutMinutes)
            LocalDateTime sessionExpiryTime = session.getSessionStartTime().plusSeconds(timeoutMinutes);

            // Check if current time has exceeded the expiry time
            LocalDateTime currentTime = LocalDateTime.now();
            boolean isExpired = currentTime.isAfter(sessionExpiryTime);

            if (isExpired) {
                LoggingUtil.logInfo(log, CLASS_NAME, "isSessionAbsoluteTimeoutExceeded", "Absolute timeout exceeded for session: %s, initiated: %s, timeout: %d minutes, expiry: %s",
                        session.getSessionId(), session.getSessionInitiatedTime(), timeoutMinutes, sessionExpiryTime);
            }

            return isExpired;
        } catch (NumberFormatException e) {
            LoggingUtil.logWarn(log, CLASS_NAME, "isSessionAbsoluteTimeoutExceeded", "Invalid sessionTimeOut format: %s. Expected numeric value in minutes. Error: %s",
                    session.getAbsoluteTimeOut().trim(), e.getMessage());
            return false;
        }
    }

}
