package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Service for tracking application metrics using Micrometer.
 */
@ApplicationScoped
public class MonitoringService {
    private static final Logger log = Logger.getLogger(MonitoringService.class);

    private final Counter sessionsCreatedCounter;
    private final Counter sessionsTerminatedCounter;
    private final Counter coaRequestsCounter;

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
     */
    public void recordCOARequest() {
        try {
            coaRequestsCounter.increment();
            log.debugf("COA request metric recorded. Total: %.0f", coaRequestsCounter.count());
        } catch (Exception e) {
            log.warnf("Failed to record COA request metric: %s", e.getMessage());
        }
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
     * Get current count of COA requests.
     * Useful for testing and debugging.
     */
    public double getCOARequestsCount() {
        return coaRequestsCounter.count();
    }
}
