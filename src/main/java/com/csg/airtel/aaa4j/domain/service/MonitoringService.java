package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized monitoring service for AAA accounting application.
 * Provides metrics for:
 * - Session lifecycle (active, created, terminated, idle)
 * - Component failures (Kafka, Redis, Database)
 * - Performance timers
 * - Error tracking
 */
@ApplicationScoped
public class MonitoringService {

    private static final Logger logger = LoggerFactory.getLogger(MonitoringService.class);

    @Inject
    MeterRegistry meterRegistry;

    // Session count metrics
    private final AtomicLong activeSessions = new AtomicLong(0);
    private final AtomicLong totalSessionsCreated = new AtomicLong(0);
    private final AtomicLong totalSessionsTerminated = new AtomicLong(0);
    private final AtomicLong totalIdleSessionsTerminated = new AtomicLong(0);

    // Component failure counters
    private Counter kafkaProduceFailures;
    private Counter kafkaConsumeFailures;
    private Counter redisFailures;
    private Counter databaseFailures;
    private Counter apiCallFailures;

    // Request type counters
    private Counter startRequests;
    private Counter interimRequests;
    private Counter stopRequests;
    private Counter coaRequests;

    // Performance timers
    private Timer startProcessingTimer;
    private Timer interimProcessingTimer;
    private Timer stopProcessingTimer;
    private Timer redisOperationTimer;
    private Timer databaseOperationTimer;
    private Timer kafkaPublishTimer;

    // Error counters
    private Counter concurrencyLimitErrors;
    private Counter quotaExceededErrors;
    private Counter validationErrors;
    private Counter systemErrors;

    /**
     * Initialize all metrics on startup
     */
    public void initialize() {
        logger.info("Initializing MonitoringService metrics");

        // Register session gauges
        Gauge.builder("sessions.active", activeSessions, AtomicLong::get)
                .description("Current number of active sessions")
                .register(meterRegistry);

        Gauge.builder("sessions.created.total", totalSessionsCreated, AtomicLong::get)
                .description("Total number of sessions created since startup")
                .register(meterRegistry);

        Gauge.builder("sessions.terminated.total", totalSessionsTerminated, AtomicLong::get)
                .description("Total number of sessions terminated since startup")
                .register(meterRegistry);

        Gauge.builder("sessions.idle_terminated", totalIdleSessionsTerminated, AtomicLong::get)
                .description("Total number of idle sessions terminated by scheduler")
                .register(meterRegistry);

        // Component failure counters
        kafkaProduceFailures = Counter.builder("component.kafka.produce.failures")
                .description("Number of Kafka produce failures")
                .tag("component", "kafka")
                .tag("operation", "produce")
                .register(meterRegistry);

        kafkaConsumeFailures = Counter.builder("component.kafka.consume.failures")
                .description("Number of Kafka consume failures")
                .tag("component", "kafka")
                .tag("operation", "consume")
                .register(meterRegistry);

        redisFailures = Counter.builder("component.redis.failures")
                .description("Number of Redis operation failures")
                .tag("component", "redis")
                .register(meterRegistry);

        databaseFailures = Counter.builder("component.database.failures")
                .description("Number of database operation failures")
                .tag("component", "database")
                .register(meterRegistry);

        apiCallFailures = Counter.builder("component.api.failures")
                .description("Number of API call failures")
                .tag("component", "api")
                .register(meterRegistry);

        // Request type counters
        startRequests = Counter.builder("requests.start.total")
                .description("Total START requests processed")
                .tag("type", "START")
                .register(meterRegistry);

        interimRequests = Counter.builder("requests.interim.total")
                .description("Total INTERIM requests processed")
                .tag("type", "INTERIM")
                .register(meterRegistry);

        stopRequests = Counter.builder("requests.stop.total")
                .description("Total STOP requests processed")
                .tag("type", "STOP")
                .register(meterRegistry);

        coaRequests = Counter.builder("requests.coa.total")
                .description("Total COA requests processed")
                .tag("type", "COA")
                .register(meterRegistry);

        // Performance timers
        startProcessingTimer = Timer.builder("processing.time")
                .description("Time to process START requests")
                .tag("type", "START")
                .register(meterRegistry);

        interimProcessingTimer = Timer.builder("processing.time")
                .description("Time to process INTERIM requests")
                .tag("type", "INTERIM")
                .register(meterRegistry);

        stopProcessingTimer = Timer.builder("processing.time")
                .description("Time to process STOP requests")
                .tag("type", "STOP")
                .register(meterRegistry);

        redisOperationTimer = Timer.builder("component.operation.time")
                .description("Time for Redis operations")
                .tag("component", "redis")
                .register(meterRegistry);

        databaseOperationTimer = Timer.builder("component.operation.time")
                .description("Time for database operations")
                .tag("component", "database")
                .register(meterRegistry);

        kafkaPublishTimer = Timer.builder("component.operation.time")
                .description("Time for Kafka publish operations")
                .tag("component", "kafka")
                .register(meterRegistry);

        // Error counters
        concurrencyLimitErrors = Counter.builder("errors.concurrency_limit")
                .description("Number of concurrency limit exceeded errors")
                .tag("error_type", "concurrency_limit")
                .register(meterRegistry);

        quotaExceededErrors = Counter.builder("errors.quota_exceeded")
                .description("Number of quota exceeded errors")
                .tag("error_type", "quota_exceeded")
                .register(meterRegistry);

        validationErrors = Counter.builder("errors.validation")
                .description("Number of validation errors")
                .tag("error_type", "validation")
                .register(meterRegistry);

        systemErrors = Counter.builder("errors.system")
                .description("Number of system errors")
                .tag("error_type", "system")
                .register(meterRegistry);

        logger.info("MonitoringService initialized successfully with {} meters", meterRegistry.getMeters().size());
    }

    // ========== Session Lifecycle Metrics ==========

    public void incrementActiveSessions() {
        activeSessions.incrementAndGet();
    }

    public void decrementActiveSessions() {
        activeSessions.decrementAndGet();
    }

    public void recordSessionCreated() {
        totalSessionsCreated.incrementAndGet();
        activeSessions.incrementAndGet();
    }

    public void recordSessionTerminated() {
        totalSessionsTerminated.incrementAndGet();
        activeSessions.decrementAndGet();
    }

    public void recordIdleSessionTerminated(long count) {
        totalIdleSessionsTerminated.addAndGet(count);
        activeSessions.addAndGet(-count);
    }

    public long getActiveSessions() {
        return activeSessions.get();
    }

    public long getTotalSessionsCreated() {
        return totalSessionsCreated.get();
    }

    public long getTotalSessionsTerminated() {
        return totalSessionsTerminated.get();
    }

    // ========== Component Failure Metrics ==========

    public void recordKafkaProduceFailure() {
        kafkaProduceFailures.increment();
    }

    public void recordKafkaConsumeFailure() {
        kafkaConsumeFailures.increment();
    }

    public void recordRedisFailure() {
        redisFailures.increment();
    }

    public void recordDatabaseFailure() {
        databaseFailures.increment();
    }

    public void recordApiCallFailure() {
        apiCallFailures.increment();
    }

    // ========== Request Type Metrics ==========

    public void recordStartRequest() {
        startRequests.increment();
    }

    public void recordInterimRequest() {
        interimRequests.increment();
    }

    public void recordStopRequest() {
        stopRequests.increment();
    }

    public void recordCoaRequest() {
        coaRequests.increment();
    }

    // ========== Performance Timers ==========

    public Timer getStartProcessingTimer() {
        return startProcessingTimer;
    }

    public Timer getInterimProcessingTimer() {
        return interimProcessingTimer;
    }

    public Timer getStopProcessingTimer() {
        return stopProcessingTimer;
    }

    public Timer getRedisOperationTimer() {
        return redisOperationTimer;
    }

    public Timer getDatabaseOperationTimer() {
        return databaseOperationTimer;
    }

    public Timer getKafkaPublishTimer() {
        return kafkaPublishTimer;
    }

    // ========== Error Counters ==========

    public void recordConcurrencyLimitError() {
        concurrencyLimitErrors.increment();
    }

    public void recordQuotaExceededError() {
        quotaExceededErrors.increment();
    }

    public void recordValidationError() {
        validationErrors.increment();
    }

    public void recordSystemError() {
        systemErrors.increment();
    }
}
