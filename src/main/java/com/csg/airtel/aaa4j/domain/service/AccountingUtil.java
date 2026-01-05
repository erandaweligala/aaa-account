package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.*;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.ConsumptionRecord;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;


@ApplicationScoped
public class AccountingUtil {
    private static final Logger log = Logger.getLogger(AccountingUtil.class);
    private static final ThreadLocal<LocalDateTime> CACHED_NOW = new ThreadLocal<>();
    private static final ThreadLocal<LocalDate> CACHED_TODAY = new ThreadLocal<>();
    private final AccountProducer accountProducer;
    private final CacheClient cacheClient;
    private final COAService coaService;
    private final QuotaNotificationService quotaNotificationService;


    public AccountingUtil(AccountProducer accountProducer, CacheClient utilCache, COAService coaService,
                          QuotaNotificationService quotaNotificationService) {
        this.accountProducer = accountProducer;
        this.cacheClient = utilCache;
        this.coaService = coaService;
        this.quotaNotificationService = quotaNotificationService;
    }

    private LocalDateTime getNow() {
        LocalDateTime now = CACHED_NOW.get();
        if (now == null) {
            now = LocalDateTime.now();
            CACHED_NOW.set(now);
        }
        return now;
    }

    private LocalDate getToday() {
        LocalDate today = CACHED_TODAY.get();
        if (today == null) {
            today = LocalDate.now();
            CACHED_TODAY.set(today);
        }
        return today;
    }

    /**
     * Clear temporal cache after request processing.
     * and ensure fresh temporal values for subsequent requests.
     */
    public void clearTemporalCache() {
        if (log.isTraceEnabled()) {
            LocalDateTime cachedNow = CACHED_NOW.get();
            LocalDate cachedToday = CACHED_TODAY.get();
            if (cachedNow != null || cachedToday != null) {
                log.tracef("Clearing temporal cache - cached now: %s, cached today: %s",
                        cachedNow, cachedToday);
            }
        }
        CACHED_NOW.remove();
        CACHED_TODAY.remove();
    }

    /**
     * Find balance with highest priority.
     *
     * @param balances user related buckets balances
     * @param bucketId specific bucket id to prioritize
     * @return balance with the highest priority
     */
    public Uni<Balance> findBalanceWithHighestPriority(List<Balance> balances, String bucketId) {

        if (log.isTraceEnabled()) {
            log.tracef("Finding balance with highest priority from %d balances", balances.size());
        }
        return Uni.createFrom().item(() -> computeHighestPriority(balances, bucketId));
    }

    /**
     * Compute highest priority balance.
     */
    private Balance computeHighestPriority(List<Balance> balances, String bucketId) {
        if (balances == null || balances.isEmpty()) {
            return null;
        }

        // Fast path: if bucketId provided, find exact match first
        if (bucketId != null) {
            for (Balance balance : balances) {
                if (bucketId.equals(balance.getBucketId())) {
                    return balance;
                }
            }
        }

        return getBalance(balances);
    }

    /**
     * Check if a balance is eligible for selection .
     *
     * @param balance the balance to check
     * @param timeWindow the time window string
     * @param now current time (cached to avoid multiple calls)
     * @return true if balance is eligible, false otherwise
     */
    private boolean isBalanceEligible(Balance balance, String timeWindow, LocalDateTime now) {

        if (balance.getQuota() <= 0 && !balance.isUnlimited()) {
            return false;
        }

        if (balance.getServiceExpiry().isBefore(now) || balance.getBucketExpiryDate().isBefore(now)) {
            return false;
        }


        if (!isWithinTimeWindow(timeWindow)) {
            return false;
        }

        // Consumption limit check (more expensive, do last)
        Long consumptionLimit = balance.getConsumptionLimit();
        Long consumptionLimitWindow = balance.getConsumptionLimitWindow();

        if (consumptionLimit != null && consumptionLimit > 0 &&
                consumptionLimitWindow != null && consumptionLimitWindow > 0) {

            long currentConsumption = calculateConsumptionInWindow(balance, consumptionLimitWindow);

            if (currentConsumption >= consumptionLimit) {
                if (log.isDebugEnabled()) {
                    log.debugf("Skipping bucket %s: consumption limit exceeded (current=%d, limit=%d)",
                            balance.getBucketId(), currentConsumption, consumptionLimit);
                }
                return false;
            }
        }

        return true;
    }

    /**
     * Get balance with highest priority .
     */
    private Balance getBalance(List<Balance> balances) {
        Balance highest = null;
        long highestPriority = Long.MIN_VALUE;
        LocalDateTime highestExpiry = null;

        LocalDateTime now = getNow();
        String activeStatus = "Active";

        for (Balance balance : balances) {
            String timeWindow = balance.getTimeWindow();

            if (!isBalanceEligible(balance, timeWindow, now)) {
                continue;
            }

            LocalDateTime serviceStartDate = balance.getServiceStartDate();

            if (!serviceStartDate.isAfter(now) && activeStatus.equals(balance.getServiceStatus())) {
                long priority = balance.getPriority();
                LocalDateTime expiry = balance.getBucketExpiryDate();

                // Select if: no current highest, lower priority, or same priority but earlier expiry
                if (highest == null || priority < highestPriority ||
                        (priority == highestPriority && expiry != null &&
                                (highestExpiry == null || expiry.isBefore(highestExpiry)))) {
                    highest = balance;
                    highestPriority = priority;
                    highestExpiry = expiry;
                }
            }
        }

        if (log.isTraceEnabled()) {
            log.tracef("Balance with highest priority selected: %s", highest != null ? highest.getBucketId() : "None");
        }
        return highest;
    }


    /**
     *
     * @param userData get user session data
     * @param sessionData get individual session Data
     * @param request packet request
     * @param bucketId bucket id
     * @return update results
     */
    public Uni<UpdateResult> updateSessionAndBalance(
            UserSessionData userData,
            Session sessionData,
            AccountingRequestDto request,
            String bucketId) {

        long totalUsage = calculateTotalUsage(request);
        String groupId = userData.getGroupId();


        if (groupId == null || AppConstant.DEFAULT_GROUP_ID.equals(groupId)) {
            return processWithoutGroupData(userData, sessionData, request, bucketId, totalUsage)
                    .eventually(this::cleanupTemporalCacheAsync);
        }

        return getGroupBucketData(groupId)
                .chain(groupData -> processWithGroupData(
                        userData,sessionData, request, bucketId, totalUsage, groupData))
                .eventually(this::cleanupTemporalCacheAsync);
    }

    /**
     * Process balance update without group data (optimized path).
     */
    private Uni<UpdateResult> processWithoutGroupData(
            UserSessionData userData,
            Session sessionData,
            AccountingRequestDto request,
            String bucketId,
            long totalUsage) {

        List<Balance> balances = userData.getBalance() != null ?
                userData.getBalance() : Collections.emptyList();
        List<Session> sessions = userData.getSessions() != null ?
                userData.getSessions() : Collections.emptyList();

        // Synchronous balance finding - no need for reactive wrapper here
        Balance foundBalance = computeHighestPriority(balances, bucketId);

        return processBalanceUpdateWithCombinedData(
                userData, sessionData, request, foundBalance,
                balances, sessions, totalUsage);
    }

    /**
     * Process balance update with group data.
     * Optimized to use efficient loops instead of stream operations.
     */
    private Uni<UpdateResult> processWithGroupData(
            UserSessionData userData,
            Session session,
            AccountingRequestDto request,
            String bucketId,
            long totalUsage,
            UserSessionData groupData) {

        List<Balance> combinedBalances = getCombinedBalancesSync(userData.getBalance(), groupData);

        Balance foundBalance = computeHighestPriority(combinedBalances, bucketId);

        List<Session> sessionsToCheck = (foundBalance != null && foundBalance.isGroup()
                && groupData != null && groupData.getSessions() != null)
                ? groupData.getSessions()
                : userData.getSessions();

        // Add session if not already present (using efficient loop instead of stream)
        if (!containsSession(sessionsToCheck, session.getSessionId())) {
            userData.getSessions().add(session);
        }

        List<Session> combinedSessions = getCombinedSessionsSync(userData.getSessions(), groupData);

        // Find session using efficient loop instead of stream
        Session sessionData = findSessionById(combinedSessions, request.sessionId());

        if (sessionData != null && sessionData.getSessionTime() != null
                && request.sessionTime() <= sessionData.getSessionTime() && AccountingRequestDto.ActionType.INTERIM_UPDATE.equals(request.actionType())) {
            if (log.isDebugEnabled()) {
                log.debugf("Skipping processing in group context: session time unchanged for sessionId: %s (request: %d, session: %d)",
                        request.sessionId(), request.sessionTime(), sessionData.getSessionTime());
            }
            return Uni.createFrom().item(UpdateResult.skipped("Session time unchanged"));
        }

        return processBalanceUpdateWithCombinedData(
                userData, sessionData, request, foundBalance,
                combinedBalances, combinedSessions, totalUsage);
    }

    /**
     *
     * @param sessions list of sessions to search
     * @param sessionId the session ID to find
     * @return true if session exists, false otherwise
     */
    private boolean containsSession(List<Session> sessions, String sessionId) {
        if (sessions == null || sessionId == null) {
            return false;
        }
        for (Session s : sessions) {
            if (sessionId.equals(s.getSessionId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find a session by ID from a list.
     * Uses efficient loop instead of stream for better performance.
     *
     * @param sessions list of sessions to search
     * @param sessionId the session ID to find
     * @return the session with matching ID, or null if not found
     */
    private Session findSessionById(List<Session> sessions, String sessionId) {
        if (sessions == null || sessionId == null) {
            return null;
        }
        for (Session s : sessions) {
            if (sessionId.equals(s.getSessionId())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Cleanup temporal cache asynchronously for use with eventually().
     */
    private Uni<Void> cleanupTemporalCacheAsync() {
        clearTemporalCache();
        return Uni.createFrom().voidItem();
    }

    private long calculateTotalUsage(AccountingRequestDto request) {
        long totalGigaWords = (long) request.outputGigaWords() + (long) request.inputGigaWords();
        long totalOctets = (long) request.inputOctets() + (long) request.outputOctets();
        return calculateTotalOctets(totalOctets, totalGigaWords);
    }

    /**
     * Calculate which window period we're currently in, based on the service start date.
     *
     * @param serviceStartDate when the service started
     * @param consumptionLimitWindow window duration in days (e.g., 30)
     * @return the current window period number (1, 2, 3, etc.)
     */
    private int getCurrentWindowPeriod(LocalDate serviceStartDate, long consumptionLimitWindow) {
        LocalDate today = getToday();

        // Calculate days since service start
        long daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(serviceStartDate, today);

        // Calculate which window we're in (1-indexed)
        int windowPeriod = (int) (daysSinceStart / consumptionLimitWindow) + 1;

        if (log.isTraceEnabled()) {
            log.tracef("Current window period: %d (days since start: %d, window size: %d days)",
                    windowPeriod, daysSinceStart, consumptionLimitWindow);
        }

        return windowPeriod;
    }

    /**
     * Calculate the start and end dates for a fixed window period.
     *
     * @param serviceStartDate when the service started
     * @param consumptionLimitWindow window duration in days
     * @return array with [windowStartDate, windowEndDate]
     */
    private LocalDate[] calculateFixedWindowBounds(LocalDate serviceStartDate, long consumptionLimitWindow) {
        int currentPeriod = getCurrentWindowPeriod(serviceStartDate, consumptionLimitWindow);

        // Calculate the start date of the current window
        LocalDate windowStartDate = serviceStartDate.plusDays((currentPeriod - 1) * consumptionLimitWindow);

        // Calculate the end date of the current window (inclusive)
        LocalDate windowEndDate = serviceStartDate.plusDays(currentPeriod * consumptionLimitWindow - 1);

        if (log.isTraceEnabled()) {
            log.tracef("Fixed window bounds: period=%d, start=%s, end=%s",
                    currentPeriod, windowStartDate, windowEndDate);
        }

        return new LocalDate[]{windowStartDate, windowEndDate};
    }

    /**
     *
     * @param balance balance containing consumption history
     * @param windowStartDate start date of the consumption window
     */
    private void cleanupOldConsumptionRecords(Balance balance, LocalDate windowStartDate) {
        List<ConsumptionRecord> history = balance.getConsumptionHistory();
        if (history == null || history.isEmpty()) {
            return;
        }

        history.removeIf(consumptionRecord -> consumptionRecord.getDate().isBefore(windowStartDate));

        if (log.isTraceEnabled() && !history.isEmpty()) {
            log.tracef("Cleaned up old consumption records for bucket %s: remaining records=%d",
                    balance.getBucketId(), history.size());
        }
    }

    /**
     * Calculate total consumption within the time window using daily aggregated records.
     *
     * @param balance balance containing consumption history and serviceStartDate
     * @param windowDays number of days for the consumption limit window (e.g., 30)
     * @return total bytes consumed within the current fixed window period
     */
    public long calculateConsumptionInWindow(Balance balance, long windowDays) {
        List<ConsumptionRecord> history = balance.getConsumptionHistory();
        if (history == null || history.isEmpty()) {
            return 0L;
        }

        // Get service start date from balance
        LocalDateTime serviceStartDateTime = balance.getServiceStartDate();
        if (serviceStartDateTime == null) {
            if (log.isDebugEnabled()) {
                log.debugf("Service start date is null for bucket %s, falling back to rolling window",
                        balance.getBucketId());
            }
            // Fallback to rolling window if serviceStartDate is not available
            return calculateRollingWindowConsumption(history, windowDays);
        }

        // Convert to LocalDate for date calculations
        LocalDate serviceStartDate = serviceStartDateTime.toLocalDate();

        // Calculate the fixed window bounds for the current period
        LocalDate[] windowBounds = calculateFixedWindowBounds(serviceStartDate, windowDays);
        LocalDate windowStartDate = windowBounds[0];
        LocalDate windowEndDate = windowBounds[1];

        // Sum consumption only within the current window period
        long total = 0L;
        for (ConsumptionRecord consumptionRecord : history) {
            LocalDate recordDate = consumptionRecord.getDate();
            // Include records within the current window (inclusive on both ends)
            if (!recordDate.isBefore(windowStartDate) && !recordDate.isAfter(windowEndDate)) {
                total += consumptionRecord.getBytesConsumed();
            }
        }

        if (log.isDebugEnabled()) {
            log.debugf("Consumption in current window for bucket %s: %d bytes (window: %s to %s)",
                    balance.getBucketId(), total, windowStartDate, windowEndDate);
        }

        return total;
    }

    /**
     * Fallback method: Calculate consumption using rolling window (legacy behavior).
     * Used when serviceStartDate is not available.
     *
     * @param history consumption history
     * @param windowDays number of days to look back
     * @return total bytes consumed in rolling window
     */
    private long calculateRollingWindowConsumption(List<ConsumptionRecord> history, long windowDays) {
        LocalDate today = getToday();
        LocalDate windowStartDate = today.minusDays(windowDays);

        long total = 0L;
        for (ConsumptionRecord consumptionRecord : history) {
            if (!consumptionRecord.getDate().isBefore(windowStartDate)) {
                total += consumptionRecord.getBytesConsumed();
            }
        }
        return total;
    }


    /**
     * Check if consumption limit is exceeded using daily aggregated records with fixed windows.
     * Cleanup is based on the current fixed window period, not rolling window.
     *
     * @param balance balance to check
     * @param previousConsumption previous consumption value
     * @param usageDelta delta usage to add
     * @return true if limit is exceeded, false otherwise
     */
    private boolean isConsumptionLimitExceeded(Balance balance, long previousConsumption, long usageDelta) {
        Long consumptionLimit = balance.getConsumptionLimit();
        Long consumptionLimitWindow = balance.getConsumptionLimitWindow();

        if (consumptionLimit == null || consumptionLimit <= 0 ||
                consumptionLimitWindow == null || consumptionLimitWindow <= 0) {
            return false;
        }

        // Clean up old records based on fixed window bounds
        LocalDateTime serviceStartDateTime = balance.getServiceStartDate();
        if (serviceStartDateTime != null) {
            LocalDate serviceStartDate = serviceStartDateTime.toLocalDate();
            LocalDate[] windowBounds = calculateFixedWindowBounds(serviceStartDate, consumptionLimitWindow);
            LocalDate windowStartDate = windowBounds[0];

            // Remove records before the current window starts
            cleanupOldConsumptionRecords(balance, windowStartDate);
        } else {
            // Fallback to rolling window cleanup
            LocalDate today = getToday();
            LocalDate windowStartDate = today.minusDays(consumptionLimitWindow);
            cleanupOldConsumptionRecords(balance, windowStartDate);
        }

        long currentConsumption = previousConsumption + usageDelta;

        if (currentConsumption > consumptionLimit) {
            if (log.isDebugEnabled()) {
                log.debugf("Consumption limit exceeded for bucket %s: current=%d, limit=%d",
                        balance.getBucketId(), currentConsumption, consumptionLimit);
            }
            return true;
        }

        return false;
    }

    /**
     * Record new consumption in balance's consumption history with daily aggregation.
     * Instead of recording each request separately (2880 records for 30 days),
     * aggregate consumption by day (30 records for 30 days).
     *
     * @param balance balance to update
     * @param bytesConsumed bytes consumed in this update
     */
    private void recordConsumption(Balance balance, long bytesConsumed) {
        List<ConsumptionRecord> history = balance.getConsumptionHistory();
        if (history == null) {
            history = new ArrayList<>(AppConstant.CONSUMPTION_HISTORY_INITIAL_CAPACITY);
            balance.setConsumptionHistory(history);
        }

        LocalDate today = getToday();

        // Find existing record for today
        ConsumptionRecord todayRecord = null;
        for (ConsumptionRecord consumptionRecord : history) {
            if (consumptionRecord.getDate().equals(today)) {
                todayRecord = consumptionRecord;
                break;
            }
        }

        if (todayRecord == null) {
            // Create new daily record
            todayRecord = new ConsumptionRecord(today, bytesConsumed, 1);
            history.add(todayRecord);

            if (log.isTraceEnabled()) {
                log.tracef("Created new daily consumption record for bucket %s: date=%s, bytes=%d",
                        balance.getBucketId(), today, bytesConsumed);
            }
        } else {
            // Aggregate into existing daily record
            todayRecord.addConsumption(bytesConsumed);

            if (log.isTraceEnabled()) {
                log.tracef("Updated daily consumption record for bucket %s: date=%s, total_bytes=%d, request_count=%d",
                        balance.getBucketId(), today, todayRecord.getBytesConsumed(), todayRecord.getRequestCount());
            }
        }
    }


    /**
     * Synchronously combine balances from user and group data.
     *
     * @param userBalances user's balances
     * @param groupData group bucket data (may be null)
     * @return combined list of balances
     */
    private List<Balance> getCombinedBalancesSync(List<Balance> userBalances, UserSessionData groupData) {
        int userSize = userBalances != null ? userBalances.size() : 0;
        List<Balance> groupBalances = groupData != null ? groupData.getBalance() : null;
        int groupSize = (groupBalances != null && !groupBalances.isEmpty()) ? groupBalances.size() : 0;

        List<Balance> combined = new ArrayList<>(userSize + groupSize);

        if (userBalances != null) {
            combined.addAll(userBalances);
        }
        if (groupBalances != null && !groupBalances.isEmpty()) {
            combined.addAll(groupBalances);
        }

        if (log.isTraceEnabled()) {
            log.tracef("Combined balances: user=%d, group=%d, total=%d", userSize, groupSize, combined.size());
        }

        return combined;
    }

    /**
     * Synchronously combine sessions from user and group data.
     *
     * @param userSessions user's sessions
     * @param groupData group bucket data (may be null)
     * @return combined list of sessions
     */
    private List<Session> getCombinedSessionsSync(List<Session> userSessions, UserSessionData groupData) {

        int userSize = userSessions != null ? userSessions.size() : 0;

        List<Session> groupSessions = groupData != null ? groupData.getSessions() : null;

        int groupSize = (groupSessions != null && !groupSessions.isEmpty()) ? groupSessions.size() : 0;

        List<Session> combined = new ArrayList<>(userSize + groupSize);

        if (userSessions != null) {
            combined.addAll(userSessions);
        }
        if (groupSessions != null && !groupSessions.isEmpty()) {
            combined.addAll(groupSessions);
        }

        if (log.isTraceEnabled()) {
            log.tracef("Combined sessions: user=%d, group=%d, total=%d", userSize, groupSize, combined.size());
        }

        return combined;
    }

    /**
     * Process balance update with combined sessions and balances from user and group.
     * This method extends the regular processBalanceUpdate by considering group bucket sessions.
     *
     * @param userData user session data
     * @param sessionData current session data
     * @param request accounting request
     * @param foundBalance balance found with highest priority
     * @param combinedBalances combined balances from user and group
     * @param combinedSessions combined sessions from user and group
     * @param totalUsage total usage for current request
     * @return Uni of UpdateResult
     */
    private Uni<UpdateResult> processBalanceUpdateWithCombinedData(
            UserSessionData userData,
            Session sessionData,
            AccountingRequestDto request,
            Balance foundBalance,
            List<Balance> combinedBalances,
            List<Session> combinedSessions,
            long totalUsage) {

        if (foundBalance == null) {
            return handleNoValidBalance(request);
        }

        if (log.isTraceEnabled()) {
            log.tracef("Processing balance update with combined data - balances: %d, sessions: %d",
                    combinedBalances.size(), combinedSessions.size());
        }




        BalanceUpdateContext context = prepareBalanceUpdateContext(
                sessionData, foundBalance, combinedBalances, totalUsage);

        long newQuota = updateQuotaForBucketChange(
                userData, sessionData, context.getEffectiveBalance(), combinedBalances,
                context.getPreviousUsageBucketId(), context.isBucketChanged(), totalUsage);

        Uni<UpdateResult> consumptionLimitResult = checkAndHandleConsumptionLimit(
                userData, request, context.getEffectiveBalance(), context.getUsageDelta(),
                newQuota, context.getPreviousUsageBucketId());

        if (consumptionLimitResult != null) {
            return consumptionLimitResult;
        }

        return finalizeBalanceUpdate(userData, sessionData, request, context.getEffectiveBalance(),
                newQuota, context.getPreviousUsageBucketId(), totalUsage);
    }

    /**
     * Handle the case when no valid balance is found.
     */
    private Uni<UpdateResult> handleNoValidBalance(AccountingRequestDto request) {
        if (log.isDebugEnabled()) {
            log.debugf("No valid balance found for user: %s", request.username());
        }
        return Uni.createFrom().item(UpdateResult.failure("error"));
    }

    /**
     * Prepare balance update context with all necessary information.
     */
    private BalanceUpdateContext prepareBalanceUpdateContext(
            Session sessionData,
            Balance foundBalance,
            List<Balance> combinedBalances,
            long totalUsage) {

        String previousUsageBucketId = getPreviousUsageBucketId(sessionData, foundBalance);
        String currentBucketId = foundBalance.getBucketId();
        boolean bucketChanged = !previousUsageBucketId.equals(currentBucketId);

        Balance effectiveBalance = determineEffectiveBalance(
                foundBalance, combinedBalances, previousUsageBucketId, bucketChanged);

        long usageDelta = calculateUsageDelta(sessionData, totalUsage);

        return new BalanceUpdateContext(previousUsageBucketId, bucketChanged, effectiveBalance, usageDelta);
    }

    /**
     * Determine which balance to use based on bucket change status.
     */
    private Balance determineEffectiveBalance(
            Balance foundBalance,
            List<Balance> combinedBalances,
            String previousUsageBucketId,
            boolean bucketChanged) {

        if (!bucketChanged) {
            return foundBalance;
        }

        Balance previousBalance = findBalanceByBucketId(combinedBalances, previousUsageBucketId);
        if (previousBalance != null) {
            if (log.isTraceEnabled()) {
                log.tracef("Bucket changed - using previous balance %s instead of new balance",
                        previousUsageBucketId);
            }
            return previousBalance;
        }

        return foundBalance;
    }

    /**
     * Calculate usage delta with null safety.
     */
    private long calculateUsageDelta(Session sessionData, long totalUsage) {
        Long previousUsageObj = sessionData.getPreviousTotalUsageQuotaValue();
        long previousUsage = (previousUsageObj != null) ? previousUsageObj : 0L;
        return Math.max(totalUsage - previousUsage, 0);
    }

    /**
     * Check consumption limit and handle if exceeded.
     *
     * @return Uni<UpdateResult> if consumption limit is exceeded, null otherwise
     */
    private Uni<UpdateResult> checkAndHandleConsumptionLimit(
            UserSessionData userData,
            AccountingRequestDto request,
            Balance balance,
            long usageDelta,
            long newQuota,
            String previousUsageBucketId) {
        if (!hasConsumptionLimit(balance)) {
            return null;
        }

        long consumptionLimitWindow = balance.getConsumptionLimitWindow();
        long previousConsumption = calculateConsumptionInWindow(balance, consumptionLimitWindow);

        if (previousConsumption < balance.getConsumptionLimit()) {
            recordConsumption(balance, usageDelta);
        }

        if (isConsumptionLimitExceeded(balance, previousConsumption, usageDelta)) {
            return handleConsumptionLimitExceededScenario(
                    userData, request, balance, newQuota, previousUsageBucketId);
        }

        return null;
    }

    /**
     * Check if balance has consumption limit configured.
     */
    private boolean hasConsumptionLimit(Balance balance) {
        Long consumptionLimit = balance.getConsumptionLimit();
        Long consumptionLimitWindow = balance.getConsumptionLimitWindow();

        return consumptionLimit != null && consumptionLimit > 0 &&
                consumptionLimitWindow != null && consumptionLimitWindow > 0;
    }

    /**
     * Handle consumption limit exceeded scenario.
     */
    private Uni<UpdateResult> handleConsumptionLimitExceededScenario(
            UserSessionData userData,
            AccountingRequestDto request,
            Balance balance,
            long newQuota,
            String previousUsageBucketId) {

        if (log.isDebugEnabled()) {
            log.debugf("Consumption limit exceeded for user: %s, bucket: %s. Triggering disconnect.",
                    request.username(), balance.getBucketId());
        }

        UpdateResult result = UpdateResult.success(newQuota, balance.getBucketId(),
                balance, previousUsageBucketId);

        return handleConsumptionLimitExceeded(userData, request, balance, result);
    }

    /**
     * Finalize balance update with session data and cache operations.
     */
    private Uni<UpdateResult> finalizeBalanceUpdate(
            UserSessionData userData,
            Session sessionData,
            AccountingRequestDto request,
            Balance balance,
            long newQuota,
            String previousUsageBucketId,
            long totalUsage) {

        updateSessionData(sessionData, balance, totalUsage, request.sessionTime());

        UpdateResult result = UpdateResult.success(newQuota, balance.getBucketId(),
                balance, previousUsageBucketId);

        if (shouldDisconnectSession(result, balance, previousUsageBucketId)) {
            return handleSessionDisconnect(userData, request, balance, result);
        }

        return updateCacheForNormalOperation(userData, request, balance, result,sessionData);
    }


    private String getPreviousUsageBucketId(Session sessionData, Balance foundBalance) {
        String previousId = sessionData.getPreviousUsageBucketId();
        return previousId != null ? previousId : foundBalance.getBucketId();
    }

    private long updateQuotaForBucketChange(
            UserSessionData userData,
            Session sessionData,
            Balance foundBalance,
            List<Balance> combinedBalances,
            String previousUsageBucketId,
            boolean bucketChanged,
            long totalUsage) {

        long newQuota;

        if (bucketChanged) {
            log.infof("Bucket changed from %s to %s for session: %s",
                    previousUsageBucketId, foundBalance.getBucketId(), sessionData.getSessionId());

            Balance previousBalance = findBalanceByBucketId(combinedBalances, previousUsageBucketId);
            newQuota = updatePreviousBucketQuota(userData, sessionData, previousBalance, totalUsage);
        } else {
            newQuota = calculateAndUpdateCurrentBucketQuota(userData, sessionData, foundBalance, totalUsage);
        }

        return Math.max(newQuota, 0);
    }

    private long updatePreviousBucketQuota(
            UserSessionData userData,
            Session sessionData,
            Balance previousBalance,
            long totalUsage) {

        if (previousBalance == null) {
            return 0;
        }

        long oldQuota = previousBalance.getQuota();
        long newQuota = getNewQuota(sessionData, previousBalance, totalUsage);
        previousBalance.setQuota(Math.max(newQuota, 0));
        replaceInCollection(userData.getBalance(), previousBalance);

        log.infof("Updated previous bucket %s quota to %d",
                previousBalance.getBucketId(), previousBalance.getQuota());

        // Check and notify quota thresholds asynchronously
        quotaNotificationService.checkAndNotifyThresholds(userData, previousBalance, oldQuota, newQuota)
                .subscribe().with(
                        unused -> {},
                        failure -> log.errorf(failure, "Failed to check thresholds for bucket %s",
                                previousBalance.getBucketId())
                );

        return newQuota;
    }

    private long calculateAndUpdateCurrentBucketQuota(
            UserSessionData userData,
            Session sessionData,
            Balance foundBalance,
            long totalUsage) {

        long oldQuota = foundBalance.getQuota();
        long newQuota = getNewQuota(sessionData, foundBalance, totalUsage);

        if (newQuota <= 0) {
            log.warnf("Quota depleted for session: %s", sessionData.getSessionId());
        }

        foundBalance.setQuota(Math.max(newQuota, 0));
        replaceInCollection(userData.getBalance(), foundBalance);
        replaceInCollection(userData.getSessions(), sessionData);

        // Check and notify quota thresholds asynchronously
        quotaNotificationService.checkAndNotifyThresholds(userData, foundBalance, oldQuota, newQuota)
                .subscribe().with(
                        unused -> {},
                        failure -> log.errorf(failure, "Failed to check thresholds for bucket %s",
                                foundBalance.getBucketId())
                );

        return newQuota;
    }

    private void updateSessionData(Session sessionData, Balance foundBalance, long totalUsage, Integer sessionTime) {
        sessionData.setPreviousTotalUsageQuotaValue(totalUsage);
        sessionData.setSessionTime(sessionTime);
        sessionData.setPreviousUsageBucketId(foundBalance.getBucketId());
        sessionData.setSessionInitiatedTime(CACHED_NOW.get());
        sessionData.setAvailableBalance(foundBalance.getQuota()); // last available balance


    }

    private boolean shouldDisconnectSession(UpdateResult result, Balance foundBalance, String previousUsageBucketId) {
        return (result.newQuota() <= 0 && !foundBalance.isUnlimited()) || !foundBalance.getBucketId().equals(previousUsageBucketId) ;
    }

    /**
     * Handle session disconnect.
     */
    private Uni<UpdateResult> handleSessionDisconnect(
            UserSessionData userData,
            AccountingRequestDto request,
            Balance foundBalance,
            UpdateResult result) {

        String username = request.username();
        String bucketUsername = foundBalance.getBucketUsername();

        if (!bucketUsername.equals(username)) {
            userData.getBalance().remove(foundBalance);
        }

        // Clear all sessions and send COA disconnect for all sessions
        return coaService.clearAllSessionsAndSendCOA(userData, username,null)
                .chain(() -> updateBalanceInDatabase(foundBalance, result.newQuota(),
                        request.sessionId(), bucketUsername, username))
                .invoke(() -> {
                    if (log.isTraceEnabled()) {
                        log.tracef("Successfully cleared all sessions and updated balance for user: %s", username);
                    }
                    userData.getSessions().clear();
                })
                .chain(() -> cacheClient.updateUserAndRelatedCaches(username, userData))
                .onFailure().invoke(err -> {
                    if (log.isDebugEnabled()) {
                        log.debugf(err, "Error clearing sessions and updating balance for user: %s", username);
                    }
                })
                .replaceWith(result);
    }

    /**
     * Handle consumption limit exceeded scenario.
     *
     * @param userData user session data
     * @param request accounting request
     * @param foundBalance balance that exceeded the limit
     * @param result update result
     * @return Uni<UpdateResult>
     */
    private Uni<UpdateResult> handleConsumptionLimitExceeded(
            UserSessionData userData,
            AccountingRequestDto request,
            Balance foundBalance,
            UpdateResult result) {

        String username = request.username();
        String bucketUsername = foundBalance.getBucketUsername();

        if (log.isDebugEnabled()) {
            log.debugf("Consumption limit exceeded for user: %s, bucket: %s. Disconnecting all sessions.",
                    username, foundBalance.getBucketId());
        }

        if (!bucketUsername.equals(username)) {
            userData.getBalance().remove(foundBalance);
        }

        // Clear all sessions and send COA disconnect for all sessions due to consumption limit
        return coaService.clearAllSessionsAndSendCOA(userData, username,null)
                .chain(() -> updateBalanceInDatabase(foundBalance, foundBalance.getQuota(),
                        request.sessionId(), bucketUsername, username))
                .invoke(() -> {
                    if (log.isTraceEnabled()) {
                        log.tracef("Successfully disconnected all sessions for user: %s due to consumption limit exceeded",
                                username);
                    }
                    userData.getSessions().clear();
                })
                .chain(() -> cacheClient.updateUserAndRelatedCaches(username, userData))
                .onFailure().invoke(err -> {
                    if (log.isDebugEnabled()) {
                        log.debugf(err, "Error disconnecting sessions for consumption limit exceeded, user: %s", username);
                    }
                })
                .replaceWith(result);
    }

    private Uni<UpdateResult> updateCacheForNormalOperation(
            UserSessionData userData,
            AccountingRequestDto request,
            Balance foundBalance,
            UpdateResult result,Session currentSession) {
        return getUpdateResultUni(userData, request, foundBalance, result,currentSession);
    }


    /**
     * Find a balance by bucket ID
     *
     * @param balances list of balances to search
     * @param bucketId the bucket ID to find
     * @return the balance with matching bucket ID, or null if not found
     */
    private Balance findBalanceByBucketId(List<Balance> balances, String bucketId) {
        if (balances == null || bucketId == null) {
            return null;
        }

        for (Balance balance : balances) {
            if (bucketId.equals(balance.getBucketId())) {
                return balance;
            }
        }
        return null;
    }

    private Uni<UpdateResult> getUpdateResultUni(UserSessionData userData, AccountingRequestDto request, Balance foundBalance, UpdateResult success,Session currentSession) {
        if(!foundBalance.getBucketUsername().equals(request.username()) ) {
            userData.getBalance().remove(foundBalance);
            userData.getSessions().remove(currentSession);
            // Fetch current group data to update sessions as well
            return cacheClient.getUserData(foundBalance.getBucketUsername())
                    .onFailure().recoverWithNull()
                    .onItem().transformToUni(existingGroupData -> {

                        UserSessionData userSessionGroupData = prepareGroupDataWithSession(
                                existingGroupData, foundBalance, currentSession,request);

                        // Update both group and user caches in parallel for better performance
                        return Uni.combine().all().unis(
                                cacheClient.updateUserAndRelatedCaches(foundBalance.getBucketUsername(), userSessionGroupData)
                                        .onFailure().invoke(err ->
                                                log.errorf(err, "Error updating Group Balance cache for user: %s", foundBalance.getBucketUsername())),
                                cacheClient.updateUserAndRelatedCaches(request.username(), userData)
                                        .onFailure().invoke(err ->
                                                log.errorf(err, "Error updating cache for user: %s", request.username()))
                        ).discardItems().replaceWith(success);
                    });
        }else {
            userData.getBalance().removeIf(Balance::isGroup);
            return cacheClient.updateUserAndRelatedCaches(request.username(), userData)
                    .onFailure().invoke(err ->
                            log.errorf(err, "Error updating cache for user: %s", request.username()))
                    .replaceWith(success);
        }
    }

    /**
     * Prepare group data with updated balance and session.
     * If session is not null, it will be added/updated in the group's sessions list.
     *
     * @param existingGroupData existing group data from cache (may be null)
     * @param balance the balance to update
     * @param session the session to add/update (may be null)
     * @return UserSessionData with updated balance and sessions
     */
    public UserSessionData prepareGroupDataWithSession(UserSessionData existingGroupData, Balance balance, Session session,AccountingRequestDto request) {
        UserSessionData groupData = new UserSessionData();
        groupData.setBalance(List.of(balance));

        if (session != null) {
            List<Session> groupSessions = new ArrayList<>();

            // If existing group data has sessions, add them first
            if (existingGroupData != null && existingGroupData.getSessions() != null) {
                // Filter out the session with the same sessionId to avoid duplicates
                groupSessions.addAll(existingGroupData.getSessions().stream()
                        .filter(s -> !s.getSessionId().equals(session.getSessionId()))
                        .toList());
            }

            // Add the current session
            if(!AccountingRequestDto.ActionType.STOP.equals(request.actionType())) {
                groupSessions.add(session);
            }
            groupData.setSessions(groupSessions);

            if (log.isDebugEnabled()) {
                log.debugf("Updated group bucket sessions: total=%d, added/updated sessionId=%s",
                        groupSessions.size(), session.getSessionId());
            }
        } else if (existingGroupData != null && existingGroupData.getSessions() != null) {
            // Preserve existing sessions if no session to add/update
            groupData.setSessions(existingGroupData.getSessions());
        }

        // Preserve groupId if it exists in the existing data
        if (existingGroupData != null && existingGroupData.getGroupId() != null) {
            groupData.setGroupId(existingGroupData.getGroupId());
        }

        return groupData;
    }

    private long getNewQuota(Session sessionData, Balance foundBalance, long totalUsage) {
        Long previousUsageObj = sessionData.getPreviousTotalUsageQuotaValue();
        long previousUsage = previousUsageObj == null ? 0L : previousUsageObj;
        long usageDelta = totalUsage - previousUsage;
        //  bucket is unlimited quota calculation
        if(foundBalance.isUnlimited()){
            foundBalance.setUsage(foundBalance.getUsage() + usageDelta);
           return totalUsage;
        }
        if (usageDelta < 0) {
            // if totalUsage is unexpectedly smaller than previous usage, clamp to 0
            usageDelta = 0;
        }
        return foundBalance.getQuota() - usageDelta;
    }

    /**
     * Replace element in collection .
     */
    private <T> void replaceInCollection(Collection<T> collection, T element) {

        if (collection instanceof List) {
            List<T> list = (List<T>) collection;
            int index = list.indexOf(element);
            if (index >= 0) {
                list.set(index, element);
                return;
            }
        }
        // Fallback for other collection types
        collection.removeIf(item -> item.equals(element));
        collection.add(element);
    }


    /**
     * Update balance in database.
     *
     * @param balance balance to update
     * @param newQuota new quota value
     * @param sessionId session ID
     * @param bucketUser bucket username
     * @param userName username
     * @return Uni<Void>
     */
    private Uni<Void> updateBalanceInDatabase(Balance balance, long newQuota, String sessionId,
                                              String bucketUser, String userName) {

        // Update balance with new quota
        balance.setQuota(Math.max(newQuota, 0));

        DBWriteRequest dbWriteRequest = MappingUtil.createDBWriteRequest(balance,userName,sessionId,EventType.UPDATE_EVENT);

        return updateGroupBalanceBucket(balance, bucketUser, userName)
                .chain(() -> accountProducer.produceDBWriteEvent(dbWriteRequest)
                        .onFailure().invoke(throwable -> {
                            if (log.isDebugEnabled()) {
                                log.debugf(throwable, "Failed to produce DB write event for balance update, session: %s",
                                        sessionId);
                            }
                        })
                );
    }

    private long calculateTotalOctets(long octets, long gigawords) {
        return (gigawords * AppConstant.GIGAWORD_MULTIPLIER) + octets;
    }

    /**
     * Update group balance bucket in cache
     */
    private Uni<Void> updateGroupBalanceBucket(Balance balance, String bucketUsername, String username) {
        if (username.equals(bucketUsername)) {
            return Uni.createFrom().voidItem();
        }

        // Create minimal UserSessionData for group update
        UserSessionData userSessionData = new UserSessionData();
        userSessionData.setBalance(List.of(balance));

        return cacheClient.updateUserAndRelatedCaches(bucketUsername, userSessionData)
                .onFailure().invoke(throwable -> {
                    if (log.isDebugEnabled()) {
                        log.debugf(throwable, "Failed to update cache group for balance update, groupId: %s",
                                bucketUsername);
                    }
                });
    }

    /**
     *
     * @param timeWindow time window string in format "HH-HH" where HH is 0-24
     * @return true if current time is within the window, false otherwise
     * @throws IllegalArgumentException if the format is invalid
     */
    public boolean isWithinTimeWindow(String timeWindow) {
        if (timeWindow == null || timeWindow.trim().isEmpty()) {
            throw new IllegalArgumentException("Time window string cannot be null or empty");
        }

        String[] times = timeWindow.split("-");

        if (times.length != 2) {
            log.errorf("Invalid time window: %s", timeWindow);
            throw new IllegalArgumentException("Invalid time window format. Expected format: 'HH-HH' (e.g., '00-24', '08-18', '0-12')");
        }

        LocalTime startTime = parseHourOnly(times[0].trim());
        LocalTime endTime = parseHourOnly(times[1].trim());
        LocalTime currentTime = LocalTime.now();

        if (startTime.isAfter(endTime)) {

            return !currentTime.isBefore(startTime) || !currentTime.isAfter(endTime);
        } else {
            return !currentTime.isBefore(startTime) && !currentTime.isAfter(endTime);
        }
    }

    /**
     *
     * @param timeStr the time string (e.g., "0", "8", "24")
     * @return LocalTime representing the hour (24 becomes 23:59:59)
     * @throws IllegalArgumentException if format is invalid or hour is out of range
     */
    private static LocalTime parseHourOnly(String timeStr) {
        timeStr = timeStr.trim();
        if (timeStr.isEmpty()) {
            throw new IllegalArgumentException("Time string cannot be empty");
        }
        try {
            int hour = Integer.parseInt(timeStr);
            if (hour == 24) {
                return LocalTime.of(23, 59, 59);
            }
            if (hour < 0 || hour > 23) {
                throw new IllegalArgumentException("Hour must be between 0 and 24, got: " + hour);
            }

            return LocalTime.of(hour, 0);

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unable to parse hour: " + timeStr +
                    ". Expected format: single or double digit hour (0-24)", e);
        }
    }



    /**
     * Get complete group bucket data including balances and sessions.
     *
     * Performance Note: This method fetches fresh group data on each call.
     * For high TPS scenarios with repeated group data access in the same request,
     * consider using .memoize().indefinitely() on the returned Uni to cache the
     * result for the duration of the request chain.
     *
     * @param groupId the group ID to fetch data for
     * @return Uni of UserSessionData for the group, or null if no group or default group
     */
    private Uni<UserSessionData> getGroupBucketData(String groupId) {
        if (groupId == null || AppConstant.DEFAULT_GROUP_ID.equals(groupId)) {
            return Uni.createFrom().nullItem();
        }

        if (log.isTraceEnabled()) {
            log.tracef("Fetching group bucket data for groupId: %s", groupId);
        }

        return cacheClient.getUserData(groupId)
                .onFailure().invoke(throwable -> {
                    if (log.isDebugEnabled()) {
                        log.debugf(throwable, "Failed to fetch group bucket data for groupId: %s", groupId);
                    }
                })
                .onFailure().recoverWithNull();
    }


}