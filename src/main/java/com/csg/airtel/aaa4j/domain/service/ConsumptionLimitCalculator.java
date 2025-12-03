package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.ConsumptionRecord;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Helper class for consumption limit calculations.
 * Handles fixed window and rolling window consumption calculations,
 * window period calculations, and cleanup operations.
 *
 * This class is stateless and thread-safe, designed for high-performance
 * consumption tracking with minimal overhead.
 */
@ApplicationScoped
public class ConsumptionLimitCalculator {

    private static final Logger log = Logger.getLogger(ConsumptionLimitCalculator.class);

    private static final ThreadLocal<LocalDateTime> CACHED_NOW = new ThreadLocal<>();
    private static final ThreadLocal<LocalDate> CACHED_TODAY = new ThreadLocal<>();

    /**
     * Get cached current time to avoid multiple LocalDateTime.now() calls
     * within the same request.
     */
    private LocalDateTime getNow() {
        LocalDateTime now = CACHED_NOW.get();
        if (now == null) {
            now = LocalDateTime.now();
            CACHED_NOW.set(now);
        }
        return now;
    }

    /**
     * Get cached current date to avoid multiple LocalDate.now() calls
     * within the same request.
     */
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
     * This method should be called at the end of each request to prevent memory leaks
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
     * Calculate total consumption within the time window using daily aggregated records.
     * Uses FIXED windows anchored to serviceStartDate, not rolling windows.
     *
     * Example: serviceStartDate = 2025-12-01, bucketExpiry = 90 days, windowDays = 30
     * - Total quota: 600GB over 90 days
     * - Window 1 (Days 0-29): 2025-12-01 to 2025-12-30 → 200GB limit
     * - Window 2 (Days 30-59): 2025-12-31 to 2026-01-29 → 200GB limit (resets)
     * - Window 3 (Days 60-89): 2026-01-30 to 2026-02-28 → 200GB limit (resets)
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
        for (ConsumptionRecord record : history) {
            LocalDate recordDate = record.getDate();
            // Include records within the current window (inclusive on both ends)
            if (!recordDate.isBefore(windowStartDate) && !recordDate.isAfter(windowEndDate)) {
                total += record.getBytesConsumed();
            }
        }

        if (log.isDebugEnabled()) {
            log.debugf("Consumption in current window for bucket %s: %d bytes (window: %s to %s)",
                    balance.getBucketId(), total, windowStartDate, windowEndDate);
        }

        return total;
    }

    /**
     * Calculate which window period we're currently in, based on the service start date.
     * For example, if service started on 2025-12-01 with 30-day windows:
     * - Window 1: Days 0-29 (2025-12-01 to 2025-12-30)
     * - Window 2: Days 30-59 (2025-12-31 to 2026-01-29)
     * - Window 3: Days 60-89 (2026-01-30 to 2026-02-28)
     *
     * @param serviceStartDate when the service started
     * @param consumptionLimitWindow window duration in days (e.g., 30)
     * @return the current window period number (1, 2, 3, etc.)
     */
    public int getCurrentWindowPeriod(LocalDate serviceStartDate, long consumptionLimitWindow) {
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
     * Windows are anchored to the service start date, not rolling.
     *
     * Example: serviceStartDate = 2025-12-01, windowDays = 30
     * - Window 1: 2025-12-01 to 2025-12-30
     * - Window 2: 2025-12-31 to 2026-01-29
     * - Window 3: 2026-01-30 to 2026-02-28
     *
     * @param serviceStartDate when the service started
     * @param consumptionLimitWindow window duration in days
     * @return array with [windowStartDate, windowEndDate]
     */
    public LocalDate[] calculateFixedWindowBounds(LocalDate serviceStartDate, long consumptionLimitWindow) {
        int currentPeriod = getCurrentWindowPeriod(serviceStartDate, consumptionLimitWindow);

        // Calculate the start date of the current window
        // Period 1 starts at serviceStartDate + 0 days
        // Period 2 starts at serviceStartDate + 30 days
        // Period 3 starts at serviceStartDate + 60 days
        LocalDate windowStartDate = serviceStartDate.plusDays((currentPeriod - 1) * consumptionLimitWindow);

        // Calculate the end date of the current window (inclusive)
        // Period 1 ends at serviceStartDate + 29 days
        // Period 2 ends at serviceStartDate + 59 days
        // Period 3 ends at serviceStartDate + 89 days
        LocalDate windowEndDate = serviceStartDate.plusDays(currentPeriod * consumptionLimitWindow - 1);

        if (log.isTraceEnabled()) {
            log.tracef("Fixed window bounds: period=%d, start=%s, end=%s",
                    currentPeriod, windowStartDate, windowEndDate);
        }

        return new LocalDate[]{windowStartDate, windowEndDate};
    }

    /**
     * Calculate the cutoff time for the consumption window using days.
     *
     * @param windowDays number of days for the consumption limit window (1, 7, 30, etc.)
     * @param now current time (cached)
     * @param today current date (cached)
     * @return LocalDateTime representing the start of the consumption window
     */
    public LocalDateTime calculateWindowStartTime(long windowDays, LocalDateTime now, LocalDate today) {
        // Calculate based on days, starting from midnight of the day windowDays ago
        return today.minusDays(windowDays).atTime(LocalTime.MIDNIGHT);
    }

    /**
     * Fallback method: Calculate consumption using rolling window (legacy behavior).
     * Used when serviceStartDate is not available.
     *
     * @param history consumption history
     * @param windowDays number of days to look back
     * @return total bytes consumed in rolling window
     */
    public long calculateRollingWindowConsumption(List<ConsumptionRecord> history, long windowDays) {
        LocalDate today = getToday();
        LocalDate windowStartDate = today.minusDays(windowDays);

        long total = 0L;
        for (ConsumptionRecord record : history) {
            if (!record.getDate().isBefore(windowStartDate)) {
                total += record.getBytesConsumed();
            }
        }
        return total;
    }

    /**
     * Clean up consumption records outside the time window using daily records.
     * Removes records older than the consumption window to maintain memory efficiency.
     *
     * @param balance balance containing consumption history
     * @param windowStartDate start date of the consumption window
     */
    public void cleanupOldConsumptionRecords(Balance balance, LocalDate windowStartDate) {
        List<ConsumptionRecord> history = balance.getConsumptionHistory();
        if (history == null || history.isEmpty()) {
            return;
        }

        // Use removeIf for efficient in-place removal
        history.removeIf(record -> record.getDate().isBefore(windowStartDate));

        if (log.isTraceEnabled() && !history.isEmpty()) {
            log.tracef("Cleaned up old consumption records for bucket %s: remaining records=%d",
                    balance.getBucketId(), history.size());
        }
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
    public boolean isConsumptionLimitExceeded(Balance balance, long previousConsumption, long usageDelta) {
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
     * Check if balance has consumption limit configured.
     *
     * @param balance balance to check
     * @return true if consumption limit is configured, false otherwise
     */
    public boolean hasConsumptionLimit(Balance balance) {
        Long consumptionLimit = balance.getConsumptionLimit();
        Long consumptionLimitWindow = balance.getConsumptionLimitWindow();

        return consumptionLimit != null && consumptionLimit > 0 &&
                consumptionLimitWindow != null && consumptionLimitWindow > 0;
    }
}
