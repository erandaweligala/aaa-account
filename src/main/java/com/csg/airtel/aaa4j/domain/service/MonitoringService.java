package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized monitoring service for tracking application metrics, errors, and health status.
 * Provides comprehensive observability for AAA accounting operations.
 */
@ApplicationScoped
public class MonitoringService {

    private static final Logger log = Logger.getLogger(MonitoringService.class);

    @Inject
    MeterRegistry registry;

    // Request Counters
    private Counter startRequestCounter;
    private Counter interimRequestCounter;
    private Counter stopRequestCounter;
    private Counter coaRequestCounter;
    private Counter authenticationRequestCounter;

    // Error Counters by Category
    private Counter intercommunicationErrorCounter;
    private Counter requestErrorCounter;
    private Counter databaseErrorCounter;
    private Counter kafkaErrorCounter;
    private Counter redisErrorCounter;
    private Counter bngErrorCounter;
    private Counter apiErrorCounter;

    // Component Failure Counters
    private Counter authFailureCounter;
    private Counter accountingFailureCounter;
    private Counter coaFailureCounter;
    private Counter sessionCreationFailureCounter;
    private Counter balanceUpdateFailureCounter;

    // Kafka Specific Metrics
    private Counter kafkaProduceErrorCounter;
    private Counter kafkaConsumeErrorCounter;
    private Counter kafkaCircuitBreakerOpenCounter;
    private Counter kafkaRetryCounter;

    // Session Metrics
    private final AtomicInteger activeSessions = new AtomicInteger(0);
    private final AtomicLong totalSessionsCreated = new AtomicLong(0);
    private final AtomicLong totalSessionsTerminated = new AtomicLong(0);
    private final AtomicInteger idleSessionsTerminated = new AtomicInteger(0);

    // Response Time Tracking
    private Timer startHandlerTimer;
    private Timer interimHandlerTimer;
    private Timer stopHandlerTimer;
    private Timer coaServiceTimer;
    private Timer databaseQueryTimer;
    private Timer kafkaProduceTimer;
    private Timer redisOperationTimer;

    // Connectivity Status (1 = connected, 0 = disconnected)
    private final AtomicInteger databaseConnectivity = new AtomicInteger(1);
    private final AtomicInteger redisConnectivity = new AtomicInteger(1);
    private final AtomicInteger kafkaConnectivity = new AtomicInteger(1);
    private final AtomicInteger bngConnectivity = new AtomicInteger(1);

    // Third-party API call tracking
    private final ConcurrentHashMap<String, Counter> apiCallCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> apiErrorCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> apiTimers = new ConcurrentHashMap<>();

    // Error categorization
    private final ConcurrentHashMap<String, Counter> errorTypeCounters = new ConcurrentHashMap<>();

    public void init() {
        log.info("Initializing MonitoringService with Micrometer metrics");

        // Initialize Request Counters
        startRequestCounter = Counter.builder("accounting.requests")
                .tag("type", "START")
                .description("Total START accounting requests")
                .register(registry);

        interimRequestCounter = Counter.builder("accounting.requests")
                .tag("type", "INTERIM")
                .description("Total INTERIM accounting requests")
                .register(registry);

        stopRequestCounter = Counter.builder("accounting.requests")
                .tag("type", "STOP")
                .description("Total STOP accounting requests")
                .register(registry);

        coaRequestCounter = Counter.builder("accounting.requests")
                .tag("type", "COA")
                .description("Total COA (Change of Authorization) requests")
                .register(registry);

        authenticationRequestCounter = Counter.builder("accounting.requests")
                .tag("type", "AUTHENTICATION")
                .description("Total authentication requests")
                .register(registry);

        // Initialize Error Counters
        intercommunicationErrorCounter = Counter.builder("application.errors")
                .tag("category", "intercommunication")
                .description("Intercommunication errors between components")
                .register(registry);

        requestErrorCounter = Counter.builder("application.errors")
                .tag("category", "request")
                .description("Request processing errors")
                .register(registry);

        databaseErrorCounter = Counter.builder("application.errors")
                .tag("category", "database")
                .description("Database connectivity and query errors")
                .register(registry);

        kafkaErrorCounter = Counter.builder("application.errors")
                .tag("category", "kafka")
                .description("Kafka connectivity and messaging errors")
                .register(registry);

        redisErrorCounter = Counter.builder("application.errors")
                .tag("category", "redis")
                .description("Redis connectivity and operation errors")
                .register(registry);

        bngErrorCounter = Counter.builder("application.errors")
                .tag("category", "bng")
                .description("BNG (Broadband Network Gateway) connectivity errors")
                .register(registry);

        apiErrorCounter = Counter.builder("application.errors")
                .tag("category", "api")
                .description("Third-party API integration errors")
                .register(registry);

        // Initialize Component Failure Counters
        authFailureCounter = Counter.builder("component.failures")
                .tag("component", "authentication")
                .description("Authentication component failures")
                .register(registry);

        accountingFailureCounter = Counter.builder("component.failures")
                .tag("component", "accounting")
                .description("Accounting component failures")
                .register(registry);

        coaFailureCounter = Counter.builder("component.failures")
                .tag("component", "coa")
                .description("COA (Change of Authorization) failures")
                .register(registry);

        sessionCreationFailureCounter = Counter.builder("component.failures")
                .tag("component", "session_creation")
                .description("Session creation failures")
                .register(registry);

        balanceUpdateFailureCounter = Counter.builder("component.failures")
                .tag("component", "balance_update")
                .description("Balance update failures")
                .register(registry);

        // Initialize Kafka Specific Metrics
        kafkaProduceErrorCounter = Counter.builder("kafka.errors")
                .tag("operation", "produce")
                .description("Kafka message production errors")
                .register(registry);

        kafkaConsumeErrorCounter = Counter.builder("kafka.errors")
                .tag("operation", "consume")
                .description("Kafka message consumption errors")
                .register(registry);

        kafkaCircuitBreakerOpenCounter = Counter.builder("kafka.circuit_breaker")
                .tag("state", "open")
                .description("Kafka circuit breaker open events")
                .register(registry);

        kafkaRetryCounter = Counter.builder("kafka.retries")
                .description("Kafka operation retry attempts")
                .register(registry);

        // Initialize Session Gauges
        Gauge.builder("sessions.active", activeSessions, AtomicInteger::get)
                .description("Current number of active sessions")
                .register(registry);

        Gauge.builder("sessions.created.total", totalSessionsCreated, AtomicLong::get)
                .description("Total sessions created since startup")
                .register(registry);

        Gauge.builder("sessions.terminated.total", totalSessionsTerminated, AtomicLong::get)
                .description("Total sessions terminated since startup")
                .register(registry);

        Gauge.builder("sessions.idle_terminated", idleSessionsTerminated, AtomicInteger::get)
                .description("Sessions terminated due to idle timeout")
                .register(registry);

        // Initialize Connectivity Gauges
        Gauge.builder("connectivity.database", databaseConnectivity, AtomicInteger::get)
                .description("Database connectivity status (1=connected, 0=disconnected)")
                .register(registry);

        Gauge.builder("connectivity.redis", redisConnectivity, AtomicInteger::get)
                .description("Redis connectivity status (1=connected, 0=disconnected)")
                .register(registry);

        Gauge.builder("connectivity.kafka", kafkaConnectivity, AtomicInteger::get)
                .description("Kafka connectivity status (1=connected, 0=disconnected)")
                .register(registry);

        Gauge.builder("connectivity.bng", bngConnectivity, AtomicInteger::get)
                .description("BNG connectivity status (1=connected, 0=disconnected)")
                .register(registry);

        // Initialize Timers
        startHandlerTimer = Timer.builder("handler.duration")
                .tag("handler", "START")
                .description("START handler execution time")
                .register(registry);

        interimHandlerTimer = Timer.builder("handler.duration")
                .tag("handler", "INTERIM")
                .description("INTERIM handler execution time")
                .register(registry);

        stopHandlerTimer = Timer.builder("handler.duration")
                .tag("handler", "STOP")
                .description("STOP handler execution time")
                .register(registry);

        coaServiceTimer = Timer.builder("handler.duration")
                .tag("handler", "COA")
                .description("COA service execution time")
                .register(registry);

        databaseQueryTimer = Timer.builder("operation.duration")
                .tag("operation", "database_query")
                .description("Database query execution time")
                .register(registry);

        kafkaProduceTimer = Timer.builder("operation.duration")
                .tag("operation", "kafka_produce")
                .description("Kafka message production time")
                .register(registry);

        redisOperationTimer = Timer.builder("operation.duration")
                .tag("operation", "redis")
                .description("Redis operation execution time")
                .register(registry);

        log.info("MonitoringService initialized successfully");
    }

    // ==================== Request Tracking ====================

    public void recordStartRequest() {
        startRequestCounter.increment();
    }

    public void recordInterimRequest() {
        interimRequestCounter.increment();
    }

    public void recordStopRequest() {
        stopRequestCounter.increment();
    }

    public void recordCoaRequest() {
        coaRequestCounter.increment();
    }

    public void recordAuthenticationRequest() {
        authenticationRequestCounter.increment();
    }

    // ==================== Error Tracking ====================

    public void recordIntercommunicationError(String source, String target, String errorType) {
        intercommunicationErrorCounter.increment();
        recordErrorByType("intercommunication." + source + "." + target, errorType);
    }

    public void recordRequestError(String requestType, String errorType) {
        requestErrorCounter.increment();
        recordErrorByType("request." + requestType, errorType);
    }

    public void recordDatabaseError(String operation, Throwable error) {
        databaseErrorCounter.increment();
        recordErrorByType("database." + operation, error.getClass().getSimpleName());
        setDatabaseConnectivity(false);
    }

    public void recordKafkaError(String operation, Throwable error) {
        kafkaErrorCounter.increment();
        recordErrorByType("kafka." + operation, error.getClass().getSimpleName());
    }

    public void recordRedisError(String operation, Throwable error) {
        redisErrorCounter.increment();
        recordErrorByType("redis." + operation, error.getClass().getSimpleName());
        setRedisConnectivity(false);
    }

    public void recordBngError(String operation, Throwable error) {
        bngErrorCounter.increment();
        recordErrorByType("bng." + operation, error.getClass().getSimpleName());
        setBngConnectivity(false);
    }

    public void recordApiError(String apiName, Throwable error) {
        apiErrorCounter.increment();
        Counter counter = apiErrorCounters.computeIfAbsent(apiName,
                name -> Counter.builder("api.errors")
                        .tag("api", name)
                        .description("Errors for API: " + name)
                        .register(registry));
        counter.increment();
        recordErrorByType("api." + apiName, error.getClass().getSimpleName());
    }

    private void recordErrorByType(String errorCategory, String errorType) {
        String key = errorCategory + "." + errorType;
        Counter counter = errorTypeCounters.computeIfAbsent(key,
                k -> Counter.builder("error.details")
                        .tag("category", errorCategory)
                        .tag("type", errorType)
                        .description("Detailed error count for " + errorCategory)
                        .register(registry));
        counter.increment();
    }

    // ==================== Component Failure Tracking ====================

    public void recordAuthenticationFailure(String reason) {
        authFailureCounter.increment();
        recordErrorByType("auth_failure", reason);
    }

    public void recordAccountingFailure(String operation, String reason) {
        accountingFailureCounter.increment();
        recordErrorByType("accounting_failure." + operation, reason);
    }

    public void recordCoaFailure(String reason) {
        coaFailureCounter.increment();
        recordErrorByType("coa_failure", reason);
    }

    public void recordSessionCreationFailure(String reason) {
        sessionCreationFailureCounter.increment();
        recordErrorByType("session_creation_failure", reason);
    }

    public void recordBalanceUpdateFailure(String reason) {
        balanceUpdateFailureCounter.increment();
        recordErrorByType("balance_update_failure", reason);
    }

    // ==================== Kafka Specific Tracking ====================

    public void recordKafkaProduceError() {
        kafkaProduceErrorCounter.increment();
    }

    public void recordKafkaConsumeError() {
        kafkaConsumeErrorCounter.increment();
    }

    public void recordKafkaCircuitBreakerOpen() {
        kafkaCircuitBreakerOpenCounter.increment();
        setKafkaConnectivity(false);
    }

    public void recordKafkaRetry() {
        kafkaRetryCounter.increment();
    }

    // ==================== Session Tracking ====================

    public void recordSessionCreated() {
        activeSessions.incrementAndGet();
        totalSessionsCreated.incrementAndGet();
    }

    public void recordSessionTerminated() {
        activeSessions.decrementAndGet();
        totalSessionsTerminated.incrementAndGet();
    }

    public void recordIdleSessionTerminated() {
        idleSessionsTerminated.incrementAndGet();
    }

    public int getActiveSessions() {
        return activeSessions.get();
    }

    // ==================== Connectivity Tracking ====================

    public void setDatabaseConnectivity(boolean connected) {
        databaseConnectivity.set(connected ? 1 : 0);
        if (connected) {
            log.debug("Database connectivity restored");
        } else {
            log.warn("Database connectivity lost");
        }
    }

    public void setRedisConnectivity(boolean connected) {
        redisConnectivity.set(connected ? 1 : 0);
        if (connected) {
            log.debug("Redis connectivity restored");
        } else {
            log.warn("Redis connectivity lost");
        }
    }

    public void setKafkaConnectivity(boolean connected) {
        kafkaConnectivity.set(connected ? 1 : 0);
        if (connected) {
            log.debug("Kafka connectivity restored");
        } else {
            log.warn("Kafka connectivity lost");
        }
    }

    public void setBngConnectivity(boolean connected) {
        bngConnectivity.set(connected ? 1 : 0);
        if (connected) {
            log.debug("BNG connectivity restored");
        } else {
            log.warn("BNG connectivity lost");
        }
    }

    // ==================== Performance Timing ====================

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordStartHandlerTime(Timer.Sample sample) {
        sample.stop(startHandlerTimer);
    }

    public void recordInterimHandlerTime(Timer.Sample sample) {
        sample.stop(interimHandlerTimer);
    }

    public void recordStopHandlerTime(Timer.Sample sample) {
        sample.stop(stopHandlerTimer);
    }

    public void recordCoaServiceTime(Timer.Sample sample) {
        sample.stop(coaServiceTimer);
    }

    public void recordDatabaseQueryTime(Timer.Sample sample) {
        sample.stop(databaseQueryTimer);
    }

    public void recordKafkaProduceTime(Timer.Sample sample) {
        sample.stop(kafkaProduceTimer);
    }

    public void recordRedisOperationTime(Timer.Sample sample) {
        sample.stop(redisOperationTimer);
    }

    // ==================== API Call Tracking ====================

    public void recordApiCall(String apiName) {
        Counter counter = apiCallCounters.computeIfAbsent(apiName,
                name -> Counter.builder("api.calls")
                        .tag("api", name)
                        .description("Total calls to API: " + name)
                        .register(registry));
        counter.increment();
    }

    public Timer.Sample startApiTimer(String apiName) {
        Timer timer = apiTimers.computeIfAbsent(apiName,
                name -> Timer.builder("api.duration")
                        .tag("api", name)
                        .description("API call duration for: " + name)
                        .register(registry));
        return Timer.start(registry);
    }

    public void recordApiCallTime(String apiName, Timer.Sample sample) {
        Timer timer = apiTimers.get(apiName);
        if (timer != null) {
            sample.stop(timer);
        }
    }

    // ==================== Getters for Health Checks ====================

    public boolean isDatabaseConnected() {
        return databaseConnectivity.get() == 1;
    }

    public boolean isRedisConnected() {
        return redisConnectivity.get() == 1;
    }

    public boolean isKafkaConnected() {
        return kafkaConnectivity.get() == 1;
    }

    public boolean isBngConnected() {
        return bngConnectivity.get() == 1;
    }
}
