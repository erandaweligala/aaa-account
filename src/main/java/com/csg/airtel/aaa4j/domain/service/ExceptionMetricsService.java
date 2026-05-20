package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Aggregates application exceptions by their root-cause type and exposes them
 * to Prometheus via Micrometer.
 *
 * <p>Three metrics are published:</p>
 * <ul>
 *   <li>{@code application_exception_count_total{exception_type, layer}} - per-layer counter,
 *       useful for drilling down into where an error surfaced (resource / service / database / client).</li>
 *   <li>{@code application_exception_root_count_total{exception_type}} - aggregated counter
 *       summing every layer where the same root-cause exception type was observed.</li>
 *   <li>{@code application_exception_percentage{exception_type}} - percentage that each
 *       exception type contributes to the total root exception volume (refreshed on each record
 *       and on a 30s schedule for safety).</li>
 * </ul>
 *
 * <p>The "root" exception type is resolved by unwrapping {@code Throwable#getCause()} until the
 * deepest cause is reached, so the same underlying failure surfaces as a single bucket even when
 * it has been wrapped at intermediate layers (e.g. a {@code SQLException} re-thrown as a
 * {@code BaseException}).</p>
 */
@ApplicationScoped
public class ExceptionMetricsService {

    private static final Logger log = Logger.getLogger(ExceptionMetricsService.class);
    private static final String M_INIT = "init";
    private static final String M_RECORD = "recordException";
    private static final String M_REFRESH = "refreshPercentages";
    private static final String M_RESET = "dailyReset";

    private static final String METRIC_PER_LAYER = "application_exception_count";
    private static final String METRIC_ROOT_TOTAL = "application_exception_root_count";
    private static final String METRIC_ROOT_DAILY = "application_exception_root_daily_count";
    private static final String METRIC_PERCENTAGE = "application_exception_percentage";
    private static final String METRIC_TOTAL = "application_exception_total";

    private static final String TAG_EXCEPTION_TYPE = "exception_type";
    private static final String TAG_LAYER = "layer";

    /** Application layers used as the {@code layer} tag value. */
    public enum Layer {
        RESOURCE("resource"),
        SERVICE("service"),
        DATABASE("database"),
        CLIENT("client"),
        LISTENER("listener"),
        PRODUCER("producer");

        private final String label;

        Layer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final MeterRegistry registry;

    // (exceptionType + '|' + layer) -> Counter
    private final ConcurrentMap<String, Counter> perLayerCounters = new ConcurrentHashMap<>();
    // exceptionType -> Counter (root, aggregated across layers)
    private final ConcurrentMap<String, Counter> rootCounters = new ConcurrentHashMap<>();
    // exceptionType -> raw root count mirror used by the percentage gauges
    private final ConcurrentMap<String, DoubleAdder> rootCountMirror = new ConcurrentHashMap<>();
    // exceptionType -> daily AtomicLong (resets at 00:00)
    private final ConcurrentMap<String, AtomicLong> dailyRootCounts = new ConcurrentHashMap<>();

    private final DoubleAdder totalRootCount = new DoubleAdder();

    private MultiGauge percentageGauge;

    @Inject
    public ExceptionMetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void init() {
        LoggingUtil.logInfo(log, M_INIT, "Initializing ExceptionMetricsService");

        this.percentageGauge = MultiGauge.builder(METRIC_PERCENTAGE)
                .description("Percentage of total application exceptions attributable to each root exception type")
                .baseUnit("percent")
                .register(registry);

        Gauge.builder(METRIC_TOTAL, totalRootCount, DoubleAdder::sum)
                .description("Sum of all root exception occurrences observed since startup")
                .register(registry);
    }

    /**
     * Records an exception observation. The root cause type is resolved by walking the cause
     * chain, and per-layer plus root counters are incremented.
     *
     * <p>Safe to call from hot paths: failures inside this method are swallowed and logged,
     * so the caller's error-handling path is never disrupted.</p>
     *
     * @param throwable the caught throwable; {@code null} is ignored
     * @param layer     the application layer where the exception was caught; {@code null} is ignored
     */
    public void recordException(Throwable throwable, Layer layer) {
        if (throwable == null || layer == null) {
            return;
        }
        try {
            String type = resolveRootCauseType(throwable);

            Counter perLayer = perLayerCounters.computeIfAbsent(
                    type + '|' + layer.label(),
                    k -> Counter.builder(METRIC_PER_LAYER)
                            .description("Application exceptions broken down by root exception type and layer")
                            .tags(Tags.of(TAG_EXCEPTION_TYPE, type, TAG_LAYER, layer.label()))
                            .register(registry));
            perLayer.increment();

            Counter root = rootCounters.computeIfAbsent(type, k -> Counter.builder(METRIC_ROOT_TOTAL)
                    .description("Application exceptions aggregated across all layers by root exception type")
                    .tag(TAG_EXCEPTION_TYPE, type)
                    .register(registry));
            root.increment();

            rootCountMirror.computeIfAbsent(type, k -> new DoubleAdder()).add(1.0);
            dailyRootCounts.computeIfAbsent(type, k -> new AtomicLong(0)).incrementAndGet();
            totalRootCount.add(1.0);

            refreshPercentages();

            LoggingUtil.logDebug(log, M_RECORD,
                    "Recorded exception type=%s layer=%s rootTotal=%s",
                    type, layer.label(), (long) totalRootCount.sum());
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record exception metric: %s", e.getMessage());
        }
    }

    /**
     * Resolves the deepest (root) cause class name, with a cycle guard.
     * Returns {@code Throwable#getClass().getSimpleName()} of the deepest cause.
     */
    static String resolveRootCauseType(Throwable t) {
        Throwable current = t;
        Set<Throwable> seen = new HashSet<>();
        while (current.getCause() != null && current.getCause() != current && seen.add(current)) {
            current = current.getCause();
        }
        String name = current.getClass().getSimpleName();
        return (name == null || name.isEmpty()) ? current.getClass().getName() : name;
    }

    /**
     * Recomputes the percentage gauge rows. Cheap (a single pass over the known exception types)
     * and synchronized on the gauge so that concurrent updates don't interleave row rewrites.
     */
    private void refreshPercentages() {
        try {
            double total = totalRootCount.sum();
            if (total <= 0.0 || percentageGauge == null) {
                return;
            }
            List<MultiGauge.Row<?>> rows = new ArrayList<>(rootCountMirror.size());
            for (Map.Entry<String, DoubleAdder> entry : rootCountMirror.entrySet()) {
                double pct = (entry.getValue().sum() / total) * 100.0;
                rows.add(MultiGauge.Row.of(Tags.of(Tag.of(TAG_EXCEPTION_TYPE, entry.getKey())), pct));
            }
            percentageGauge.register(rows, true);
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_REFRESH, "Failed to refresh percentage gauges: %s", e.getMessage());
        }
    }

    /**
     * Safety-net refresh in case a burst of {@code recordException} calls raced and the last
     * {@link #refreshPercentages()} observed stale totals. Cheap to run on a schedule.
     */
    @Scheduled(every = "30s")
    void scheduledRefresh() {
        refreshPercentages();
    }

    /**
     * Resets the 24-hour daily exception counters at midnight. Lifetime counters are untouched.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    void resetDailyCounters() {
        LoggingUtil.logInfo(log, M_RESET, "Resetting daily exception counters");
        for (AtomicLong v : dailyRootCounts.values()) {
            v.set(0);
        }
    }

    /**
     * Returns an immutable snapshot of {@code exceptionType -> {count, percentage, daily}}
     * suitable for serialising over HTTP. Iteration order is by descending count.
     */
    public Map<String, ExceptionStats> snapshot() {
        double total = totalRootCount.sum();
        List<Map.Entry<String, DoubleAdder>> entries = new ArrayList<>(rootCountMirror.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue().sum(), a.getValue().sum()));

        Map<String, ExceptionStats> out = new LinkedHashMap<>(entries.size());
        for (Map.Entry<String, DoubleAdder> e : entries) {
            double count = e.getValue().sum();
            double pct = total > 0.0 ? (count / total) * 100.0 : 0.0;
            long daily = dailyRootCounts.getOrDefault(e.getKey(), new AtomicLong(0)).get();
            out.put(e.getKey(), new ExceptionStats((long) count, pct, daily));
        }
        return Collections.unmodifiableMap(out);
    }

    public long getTotalRootCount() {
        return (long) totalRootCount.sum();
    }

    /** Immutable view of one exception type's aggregated stats. */
    public record ExceptionStats(long count, double percentage, long dailyCount) {}
}
