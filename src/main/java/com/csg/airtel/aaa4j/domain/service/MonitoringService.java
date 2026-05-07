package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.coa.CoaDisconnectScenario;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class MonitoringService {
    private static final Logger log = Logger.getLogger(MonitoringService.class);
    private static final String M_INIT = "init";
    private static final String M_RECORD = "recordMetric";
    private static final String M_DAILY = "dailyCount";
    private static final String M_RESET = "dailyReset";

    private static final String REDIS_KEY_SESSION_CREATED    = "dailyOpenSessionCount";
    private static final String REDIS_KEY_SESSION_TERMINATED = "dailySessionTerminatedCount";
    private static final String REDIS_KEY_DISCONNECT_SUCCESS = "coaRequestCount";
    private static final String REDIS_KEY_DISCONNECT_FAILURE = "dailyDisconnectFailureCount";
    private static final String REDIS_KEY_COA_SYSTEM_FAILURE = "dailyCoaSystemFailureCount";
    private static final String REDIS_KEY_COA_INITIATED_PREFIX = "dailyCoaInitiatedCount:";

    private static final String METRIC_COA_INITIATED_TOTAL = "coa_disconnect_request_initiated_count";
    private static final String METRIC_COA_INITIATED_DAILY = "coa_disconnect_request_initiated_daily_count";
    private static final String TAG_SCENARIO = "scenario";

    // Lifetime Micrometer counters exposed to Prometheus
    private final Counter sessionsCreatedCounter;
    private final Counter sessionsTerminatedCounter;
    private final Counter disconnectSuccessCounter;
    private final Counter disconnectFailureCounter;
    private final Counter coaSystemFailureCounter;

    // 24-hour window counters (00:00–23:59), reset by scheduler at midnight
    private final AtomicLong dailySessionCreatedCount;
    private final AtomicLong dailySessionTerminatedCount;
    private final AtomicLong dailyDisconnectSuccessCount;
    private final AtomicLong dailyDisconnectFailureCount;
    private final AtomicLong dailyCoaSystemFailureCount;

    // Per-scenario lifetime counters and 24-hour window counters for COA-Disconnect requests initiated
    private final Map<CoaDisconnectScenario, Counter> coaInitiatedCounters;
    private final Map<CoaDisconnectScenario, AtomicLong> dailyCoaInitiatedCounts;

    private volatile LocalDate currentDay;

    private final ReactiveValueCommands<String, Long> redisValueCommands;

    @Inject
    public MonitoringService(MeterRegistry registry, ReactiveRedisDataSource reactiveRedisDataSource) {
        LoggingUtil.logInfo(log, M_INIT, "Initializing MonitoringService with Micrometer metrics");

        // Lifetime counters (cumulative, never reset)
        this.sessionsCreatedCounter = Counter.builder("open_session_count")
                .description("Total number of sessions created")
                .register(registry);

        this.sessionsTerminatedCounter = Counter.builder("session_terminated_count")
                .description("Total number of sessions terminated")
                .register(registry);

        this.disconnectSuccessCounter = Counter.builder("disconnect_request_success_count")
                .description("Total number of successful COA disconnect requests")
                .register(registry);

        this.disconnectFailureCounter = Counter.builder("disconnect_request_failure_count")
                .description("Total number of COA disconnect requests rejected by NAS with NAK")
                .register(registry);

        this.coaSystemFailureCounter = Counter.builder("coa_system_failure_count")
                .description("Total number of COA disconnect system failures (HTTP errors, producer errors, exceptions)")
                .register(registry);

        // Daily AtomicLong counters reset at 00:00 by scheduler
        this.dailySessionCreatedCount    = new AtomicLong(0);
        this.dailySessionTerminatedCount = new AtomicLong(0);
        this.dailyDisconnectSuccessCount = new AtomicLong(0);
        this.dailyDisconnectFailureCount = new AtomicLong(0);
        this.dailyCoaSystemFailureCount  = new AtomicLong(0);
        this.currentDay = LocalDate.now();

        // Prometheus Gauges for 24-hour window counts
        Gauge.builder("open_session_daily_count", dailySessionCreatedCount, AtomicLong::get)
                .description("Sessions opened in the current 24-hour window (resets at 00:00)")
                .register(registry);

        Gauge.builder("session_terminated_daily_count", dailySessionTerminatedCount, AtomicLong::get)
                .description("Sessions terminated in the current 24-hour window (resets at 00:00)")
                .register(registry);

        Gauge.builder("disconnect_request_success_daily_count", dailyDisconnectSuccessCount, AtomicLong::get)
                .description("Successful disconnect requests in the current 24-hour window (resets at 00:00)")
                .register(registry);

        Gauge.builder("disconnect_request_failure_daily_count", dailyDisconnectFailureCount, AtomicLong::get)
                .description("NAK disconnect responses in the current 24-hour window (resets at 00:00)")
                .register(registry);

        Gauge.builder("coa_system_failure_daily_count", dailyCoaSystemFailureCount, AtomicLong::get)
                .description("COA system failures in the current 24-hour window (resets at 00:00)")
                .register(registry);

        // Per-scenario COA-Disconnect initiated counters (lifetime + daily window)
        this.coaInitiatedCounters = new EnumMap<>(CoaDisconnectScenario.class);
        this.dailyCoaInitiatedCounts = new EnumMap<>(CoaDisconnectScenario.class);
        for (CoaDisconnectScenario scenario : CoaDisconnectScenario.values()) {
            Counter lifetime = Counter.builder(METRIC_COA_INITIATED_TOTAL)
                    .description("Total number of COA-Disconnect requests initiated, tagged by scenario")
                    .tag(TAG_SCENARIO, scenario.label())
                    .register(registry);
            coaInitiatedCounters.put(scenario, lifetime);

            AtomicLong dailyCount = new AtomicLong(0);
            dailyCoaInitiatedCounts.put(scenario, dailyCount);
            Gauge.builder(METRIC_COA_INITIATED_DAILY, dailyCount, AtomicLong::get)
                    .description("COA-Disconnect requests initiated in the current 24-hour window (resets at 00:00), tagged by scenario")
                    .tag(TAG_SCENARIO, scenario.label())
                    .register(registry);
        }

        this.redisValueCommands = reactiveRedisDataSource.value(String.class, Long.class);

        LoggingUtil.logInfo(log, M_INIT, "MonitoringService initialized successfully");
    }

    public void recordSessionCreated() {
        try {
            sessionsCreatedCounter.increment();
            incrementDailyCount(REDIS_KEY_SESSION_CREATED, dailySessionCreatedCount);
            LoggingUtil.logDebug(log, M_RECORD, "Session created metric recorded. Total: %.0f, Daily: %d",
                    sessionsCreatedCounter.count(), dailySessionCreatedCount.get());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record session created metric: %s", e.getMessage());
        }
    }

    public void recordSessionTerminated() {
        try {
            sessionsTerminatedCounter.increment();
            incrementDailyCount(REDIS_KEY_SESSION_TERMINATED, dailySessionTerminatedCount);
            LoggingUtil.logDebug(log, M_RECORD, "Session terminated metric recorded. Total: %.0f, Daily: %d",
                    sessionsTerminatedCounter.count(), dailySessionTerminatedCount.get());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record session terminated metric: %s", e.getMessage());
        }
    }

    public void recordIdleSessionsTerminated(int count) {
        try {
            if (count > 0) {
                sessionsTerminatedCounter.increment(count);
                dailySessionTerminatedCount.addAndGet(count);
                redisValueCommands.incrby(REDIS_KEY_SESSION_TERMINATED, (long) count)
                        .subscribe().with(
                                v -> LoggingUtil.logDebug(log, M_RECORD, "Redis session terminated count incremented by %d", count),
                                error -> LoggingUtil.logWarn(log, M_RECORD, "Failed to increment Redis terminated count by %d: %s", count, error.getMessage())
                        );
                LoggingUtil.logInfo(log, M_RECORD, "Idle sessions terminated metric recorded. Count: %d, Total: %.0f",
                        count, sessionsTerminatedCounter.count());
            }
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record idle sessions terminated metric: %s", e.getMessage());
        }
    }

    public void recordCOARequest() {
        try {
            disconnectSuccessCounter.increment();
            incrementDailyCount(REDIS_KEY_DISCONNECT_SUCCESS, dailyDisconnectSuccessCount);
            LoggingUtil.logDebug(log, M_RECORD, "COA request metric recorded. Total: %.0f, Daily: %d",
                    disconnectSuccessCounter.count(), dailyDisconnectSuccessCount.get());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record COA request metric: %s", e.getMessage());
        }
    }

    /**
     * Records a COA-Disconnect request initiation for a given business scenario.
     * Increments both the cumulative and daily Prometheus counters tagged with the scenario,
     * and increments the per-scenario daily count in Redis.
     */
    public void recordCOADisconnectInitiated(CoaDisconnectScenario scenario) {
        if (scenario == null) {
            return;
        }
        try {
            Counter counter = coaInitiatedCounters.get(scenario);
            AtomicLong dailyCount = dailyCoaInitiatedCounts.get(scenario);
            if (counter == null || dailyCount == null) {
                return;
            }
            counter.increment();
            dailyCount.incrementAndGet();
            String redisKey = REDIS_KEY_COA_INITIATED_PREFIX + scenario.label();
            redisValueCommands.incr(redisKey)
                    .subscribe().with(
                            v -> LoggingUtil.logDebug(log, M_DAILY, "Redis key %s incremented", redisKey),
                            error -> LoggingUtil.logWarn(log, M_DAILY, "Failed to increment Redis key %s: %s", redisKey, error.getMessage())
                    );
            LoggingUtil.logDebug(log, M_RECORD, "COA-Disconnect initiated metric recorded for scenario %s. Total: %.0f, Daily: %d",
                    scenario.label(), counter.count(), dailyCount.get());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record COA-Disconnect initiated metric for scenario %s: %s",
                    scenario, e.getMessage());
        }
    }

    public void recordDisconnectRequestFailure() {
        try {
            disconnectFailureCounter.increment();
            incrementDailyCount(REDIS_KEY_DISCONNECT_FAILURE, dailyDisconnectFailureCount);
            LoggingUtil.logDebug(log, M_RECORD, "Disconnect NAK metric recorded. Total: %.0f, Daily: %d",
                    disconnectFailureCounter.count(), dailyDisconnectFailureCount.get());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record disconnect NAK metric: %s", e.getMessage());
        }
    }

    /**
     * Records a COA system failure (HTTP error, producer error, or unexpected exception).
     * This is tracked separately from NAK responses, which represent valid business-level rejections.
     */
    public void recordCOASystemFailure() {
        try {
            coaSystemFailureCounter.increment();
            incrementDailyCount(REDIS_KEY_COA_SYSTEM_FAILURE, dailyCoaSystemFailureCount);
            LoggingUtil.logDebug(log, M_RECORD, "COA system failure metric recorded. Total: %.0f, Daily: %d",
                    coaSystemFailureCounter.count(), dailyCoaSystemFailureCount.get());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record COA system failure metric: %s", e.getMessage());
        }
    }

    /**
     * Resets all 24-hour window counters at midnight (00:00:00).
     * Both in-memory AtomicLong values and Redis keys are cleared.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    void resetDailyCounters() {
        LoggingUtil.logInfo(log, M_RESET,
                "Resetting daily metric counters at midnight. Previous counts — created: %d, terminated: %d, success: %d, nak: %d, systemFailure: %d",
                dailySessionCreatedCount.get(), dailySessionTerminatedCount.get(),
                dailyDisconnectSuccessCount.get(), dailyDisconnectFailureCount.get(),
                dailyCoaSystemFailureCount.get());

        dailySessionCreatedCount.set(0);
        dailySessionTerminatedCount.set(0);
        dailyDisconnectSuccessCount.set(0);
        dailyDisconnectFailureCount.set(0);
        dailyCoaSystemFailureCount.set(0);
        for (Map.Entry<CoaDisconnectScenario, AtomicLong> entry : dailyCoaInitiatedCounts.entrySet()) {
            entry.getValue().set(0);
            resetRedisKey(REDIS_KEY_COA_INITIATED_PREFIX + entry.getKey().label());
        }
        currentDay = LocalDate.now();

        resetRedisKey(REDIS_KEY_SESSION_CREATED);
        resetRedisKey(REDIS_KEY_SESSION_TERMINATED);
        resetRedisKey(REDIS_KEY_DISCONNECT_SUCCESS);
        resetRedisKey(REDIS_KEY_DISCONNECT_FAILURE);
        resetRedisKey(REDIS_KEY_COA_SYSTEM_FAILURE);

        LoggingUtil.logInfo(log, M_RESET, "Daily metric counters reset successfully for date: %s", currentDay);
    }

    // ---- Helpers ----

    private void incrementDailyCount(String redisKey, AtomicLong localCount) {
        localCount.incrementAndGet();
        redisValueCommands.incr(redisKey)
                .subscribe().with(
                        v -> LoggingUtil.logDebug(log, M_DAILY, "Redis key %s incremented", redisKey),
                        error -> LoggingUtil.logWarn(log, M_DAILY, "Failed to increment Redis key %s: %s", redisKey, error.getMessage())
                );
    }

    private void resetRedisKey(String redisKey) {
        redisValueCommands.set(redisKey, 0L)
                .subscribe().with(
                        v -> LoggingUtil.logDebug(log, M_RESET, "Redis key %s reset to 0", redisKey),
                        error -> LoggingUtil.logWarn(log, M_RESET, "Failed to reset Redis key %s: %s", redisKey, error.getMessage())
                );
    }

    private Uni<Long> getDailyCount(String redisKey, AtomicLong fallback) {
        return redisValueCommands.get(redisKey)
                .onItem().transform(count -> {
                    if (count != null) {
                        LoggingUtil.logDebug(log, M_DAILY, "Retrieved daily count from Redis key %s: %d", redisKey, count);
                        return count;
                    }
                    long inMemory = fallback.get();
                    LoggingUtil.logDebug(log, M_DAILY, "Redis cache empty for %s, using in-memory: %d", redisKey, inMemory);
                    return inMemory;
                })
                .onFailure().recoverWithItem(() -> {
                    long inMemory = fallback.get();
                    LoggingUtil.logWarn(log, M_DAILY, "Redis failure for %s, using in-memory: %d", redisKey, inMemory);
                    return inMemory;
                });
    }

    // ---- Getters (testing / debugging / REST exposure) ----

    public double getSessionsCreatedCount() {
        return sessionsCreatedCounter.count();
    }

    public double getSessionsTerminatedCount() {
        return sessionsTerminatedCounter.count();
    }

    public double getCOARequestsCount() {
        return disconnectSuccessCounter.count();
    }

    public double getDisconnectFailureCount() {
        return disconnectFailureCounter.count();
    }

    public double getCOASystemFailureCount() {
        return coaSystemFailureCounter.count();
    }

    public Uni<Long> getDailyCoaRequestCount() {
        return getDailyCount(REDIS_KEY_DISCONNECT_SUCCESS, dailyDisconnectSuccessCount);
    }

    public Uni<Long> getDailySessionCreatedCount() {
        return getDailyCount(REDIS_KEY_SESSION_CREATED, dailySessionCreatedCount);
    }

    public Uni<Long> getDailySessionTerminatedCount() {
        return getDailyCount(REDIS_KEY_SESSION_TERMINATED, dailySessionTerminatedCount);
    }

    public Uni<Long> getDailyDisconnectFailureCount() {
        return getDailyCount(REDIS_KEY_DISCONNECT_FAILURE, dailyDisconnectFailureCount);
    }

    public Uni<Long> getDailyCOASystemFailureCount() {
        return getDailyCount(REDIS_KEY_COA_SYSTEM_FAILURE, dailyCoaSystemFailureCount);
    }

    public Uni<Long> getDailyCoaInitiatedCount(CoaDisconnectScenario scenario) {
        AtomicLong fallback = dailyCoaInitiatedCounts.get(scenario);
        if (fallback == null) {
            return Uni.createFrom().item(0L);
        }
        return getDailyCount(REDIS_KEY_COA_INITIATED_PREFIX + scenario.label(), fallback);
    }
}
