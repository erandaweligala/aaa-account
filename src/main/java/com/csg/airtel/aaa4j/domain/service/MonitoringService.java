package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for tracking application metrics using Micrometer.
 */
@ApplicationScoped
public class MonitoringService {
    private static final Logger log = Logger.getLogger(MonitoringService.class);
    private static final String CLASS_NAME = MonitoringService.class.getSimpleName();
    private static final String COA_REQUEST_COUNT_CACHE_KEY = "coaRequestCount";

    private final Counter sessionsCreatedCounter;
    private final Counter sessionsTerminatedCounter;
    private final Counter coaRequestsCounter;

    private volatile LocalDate currentDay;
    private final AtomicLong dailyCoaRequestCount;

    // Redis cache for distributed daily COA count tracking
    private final ReactiveValueCommands<String, Long> redisValueCommands;

    @Inject
    public MonitoringService(MeterRegistry registry, ReactiveRedisDataSource reactiveRedisDataSource) {
        LoggingUtil.logInfo(log, CLASS_NAME, "MonitoringService", "Initializing MonitoringService with Micrometer metrics");

        // Initialize counters
        this.sessionsCreatedCounter = Counter.builder("sessions.created.total")
                .description("Total number of sessions created")
                .register(registry);

        this.sessionsTerminatedCounter = Counter.builder("sessions.terminated.total")
                .description("Total number of sessions terminated")
                .register(registry);

        this.coaRequestsCounter = Counter.builder("coa.requests.total")
                .description("Total number of COA (Change of Authorization) requests sent")
                .register(registry);

        // Initialize COA request cache for 24-hour tracking
        this.currentDay = LocalDate.now();
        this.dailyCoaRequestCount = new AtomicLong(0);

        // Initialize Redis cache commands for distributed daily count tracking
        this.redisValueCommands = reactiveRedisDataSource.value(String.class, Long.class);

        LoggingUtil.logInfo(log, CLASS_NAME, "MonitoringService", "MonitoringService initialized successfully");
    }

    /**
     * Records a session creation event.
     * Should be called from SessionLifecycleManager.onSessionCreated()
     */
    public void recordSessionCreated() {
        try {
            sessionsCreatedCounter.increment();
            LoggingUtil.logDebug(log, CLASS_NAME, "recordSessionCreated", "Session created metric recorded. Total: %.0f", sessionsCreatedCounter.count());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, CLASS_NAME, "recordSessionCreated", "Failed to record session created metric: %s", e.getMessage());
        }
    }

    /**
     * Records a session termination event.
     * Should be called from SessionLifecycleManager.onSessionTerminated()
     * and IdleSessionTerminatorScheduler for idle sessions.
     */
    public void recordSessionTerminated() {
        try {
            sessionsTerminatedCounter.increment();
            LoggingUtil.logDebug(log, CLASS_NAME, "recordSessionTerminated", "Session terminated metric recorded. Total: %.0f", sessionsTerminatedCounter.count());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, CLASS_NAME, "recordSessionTerminated", "Failed to record session terminated metric: %s", e.getMessage());
        }
    }

    /**
     * Records multiple idle session termination events.
     * Should be called from IdleSessionTerminatorScheduler after batch processing.
     *
     * @param count Number of idle sessions terminated in the batch
     */
    public void recordIdleSessionsTerminated(int count) {
        try {
            if (count > 0) {
                sessionsTerminatedCounter.increment(count);
                LoggingUtil.logInfo(log, CLASS_NAME, "recordIdleSessionsTerminated", "Idle sessions terminated metric recorded. Count: %d, Total: %.0f",
                        count, sessionsTerminatedCounter.count());
            }
        } catch (Exception e) {
            LoggingUtil.logWarn(log, CLASS_NAME, "recordIdleSessionsTerminated", "Failed to record idle sessions terminated metric: %s", e.getMessage());
        }
    }

    /**
     * Records a COA (Change of Authorization) request.
     * Should be called from COAService when sending COA disconnect events.
     * Tracks both lifetime total and daily count (24-hour window from 00:00 to 24:00).
     */
    public void recordCOARequest() {
        try {
            // Increment lifetime counter
            coaRequestsCounter.increment();

            // Update daily cache with automatic reset at midnight
            updateDailyCoaCount();

            LoggingUtil.logDebug(log, CLASS_NAME, "recordCOARequest", "COA request metric recorded. Total: %.0f, Daily: %d",
                    coaRequestsCounter.count(), dailyCoaRequestCount.get());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, CLASS_NAME, "recordCOARequest", "Failed to record COA request metric: %s", e.getMessage());
        }
    }

    /**
     * Updates the daily COA request count.
     * Resets the counter if a new day has started (00:00).
     * Syncs the count with Redis for distributed tracking.
     */
    private synchronized void updateDailyCoaCount() {
        LocalDate today = LocalDate.now();

        // Check if we've moved to a new day
        if (!today.equals(currentDay)) {
            LoggingUtil.logInfo(log, CLASS_NAME, "updateDailyCoaCount", "New day detected. Resetting COA daily count. Previous day: %s, Count: %d",
                    currentDay, dailyCoaRequestCount.get());
            currentDay = today;
            dailyCoaRequestCount.set(0);

            // Reset Redis cache for new day
            redisValueCommands.set(COA_REQUEST_COUNT_CACHE_KEY, 0L)
                    .subscribe().with(
                            success -> LoggingUtil.logDebug(log, CLASS_NAME, "updateDailyCoaCount", "Redis COA count reset for new day"),
                            error -> LoggingUtil.logWarn(log, CLASS_NAME, "updateDailyCoaCount", "Failed to reset Redis COA count: %s", error.getMessage())
                    );
        }

        // Increment daily count in memory
        long newCount = dailyCoaRequestCount.incrementAndGet();

        // Increment Redis cache (fire and forget for performance)
        redisValueCommands.incr(COA_REQUEST_COUNT_CACHE_KEY)
                .subscribe().with(
                        redisCount -> LoggingUtil.logDebug(log, CLASS_NAME, "updateDailyCoaCount", "Redis COA count incremented (local: %d)", newCount),
                        error -> LoggingUtil.logWarn(log, CLASS_NAME, "updateDailyCoaCount", "Failed to increment Redis COA count: %s", error.getMessage())
                );
    }

    /**
     * Get current count of sessions created.
     * Useful for testing and debugging.
     */
    public double getSessionsCreatedCount() {
        return sessionsCreatedCounter.count();
    }

    /**
     * Get current count of sessions terminated.
     * Useful for testing and debugging.
     */
    public double getSessionsTerminatedCount() {
        return sessionsTerminatedCounter.count();
    }

    /**
     * Get current count of COA requests (lifetime total).
     * Useful for testing and debugging.
     */
    public double getCOARequestsCount() {
        return coaRequestsCounter.count();
    }

    /**
     * Get the daily COA request count for the current 24-hour period (00:00 to 24:00).
     * The count automatically resets at midnight.
     * Reads from Redis cache (key: coaRequestCount) for distributed tracking.
     * Falls back to in-memory count if Redis is unavailable.
     * Useful for monitoring and debugging.
     *
     * @return Uni containing current day's COA request count
     */
    public Uni<Long> getDailyCoaRequestCount() {
        // Check if day has changed
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            // Day has changed but updateDailyCoaCount hasn't been called yet
            LoggingUtil.logDebug(log, CLASS_NAME, "getDailyCoaRequestCount", "Day has changed, returning 0 for COA count");
            return Uni.createFrom().item(0L);
        }

        // Fetch from Redis cache (primary source for distributed systems)
        return redisValueCommands.get(COA_REQUEST_COUNT_CACHE_KEY)
                .onItem().transform(count -> {
                    if (count != null) {
                        LoggingUtil.logDebug(log, CLASS_NAME, "getDailyCoaRequestCount", "Retrieved daily COA count from Redis: %d", count);
                        return count;
                    } else {
                        // Redis cache not initialized yet, return in-memory count
                        long inMemoryCount = dailyCoaRequestCount.get();
                        LoggingUtil.logDebug(log, CLASS_NAME, "getDailyCoaRequestCount", "Redis cache empty, using in-memory count: %d", inMemoryCount);
                        return inMemoryCount;
                    }
                })
                .onFailure().recoverWithItem(() -> {
                    // Fall back to in-memory count if Redis fails
                    long inMemoryCount = dailyCoaRequestCount.get();
                    LoggingUtil.logWarn(log, CLASS_NAME, "getDailyCoaRequestCount", "Failed to retrieve COA count from Redis, using in-memory count: %d", inMemoryCount);
                    return inMemoryCount;
                });
    }

}
