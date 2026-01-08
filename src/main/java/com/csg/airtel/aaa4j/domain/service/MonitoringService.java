package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    private final Counter sessionsCreatedCounter;
    private final Counter sessionsTerminatedCounter;
    private final Counter coaRequestsCounter;

    // COA request cache for 24-hour tracking (00:00 to 24:00)
    private volatile LocalDate currentDay;
    private final AtomicLong dailyCoaRequestCount;

    @Inject
    public MonitoringService(MeterRegistry registry) {
        log.info("Initializing MonitoringService with Micrometer metrics");

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

        log.info("MonitoringService initialized successfully");
    }

    /**
     * Records a session creation event.
     * Should be called from SessionLifecycleManager.onSessionCreated()
     */
    public void recordSessionCreated() {
        try {
            sessionsCreatedCounter.increment();
            log.debugf("Session created metric recorded. Total: %.0f", sessionsCreatedCounter.count());
        } catch (Exception e) {
            log.warnf("Failed to record session created metric: %s", e.getMessage());
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
            log.debugf("Session terminated metric recorded. Total: %.0f", sessionsTerminatedCounter.count());
        } catch (Exception e) {
            log.warnf("Failed to record session terminated metric: %s", e.getMessage());
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
                log.infof("Idle sessions terminated metric recorded. Count: %d, Total: %.0f",
                        count, sessionsTerminatedCounter.count());
            }
        } catch (Exception e) {
            log.warnf("Failed to record idle sessions terminated metric: %s", e.getMessage());
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

            log.debugf("COA request metric recorded. Total: %.0f, Daily: %d",
                    coaRequestsCounter.count(), dailyCoaRequestCount.get());
        } catch (Exception e) {
            log.warnf("Failed to record COA request metric: %s", e.getMessage());
        }
    }

    /**
     * Updates the daily COA request count.
     * Resets the counter if a new day has started (00:00).
     */
    private synchronized void updateDailyCoaCount() {
        LocalDate today = LocalDate.now();

        // Check if we've moved to a new day
        if (!today.equals(currentDay)) {
            log.infof("New day detected. Resetting COA daily count. Previous day: %s, Count: %d",
                    currentDay, dailyCoaRequestCount.get());
            currentDay = today;
            dailyCoaRequestCount.set(0);
        }

        // Increment daily count
        dailyCoaRequestCount.incrementAndGet();
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
     * Useful for monitoring and debugging.
     *
     * @return Current day's COA request count
     */
    public long getDailyCoaRequestCount() {
        // Ensure we're looking at today's count
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            // Day has changed but updateDailyCoaCount hasn't been called yet
            return 0;
        }
        return dailyCoaRequestCount.get();
    }

    /**
     * Get the current day being tracked for daily COA count.
     * Useful for testing and debugging.
     *
     * @return Current day being tracked
     */
    public LocalDate getCurrentDay() {
        return currentDay;
    }
}
