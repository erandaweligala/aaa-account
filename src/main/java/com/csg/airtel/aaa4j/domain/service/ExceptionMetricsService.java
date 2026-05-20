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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Aggregates application exceptions by their root-cause type and exposes them
 * to Prometheus via Micrometer.
 * walks the full type map under load.</p>
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
    private static final String METRIC_PERCENTAGE = "application_exception_percentage";
    private static final String METRIC_TOTAL = "application_exception_total";

    private static final String TAG_EXCEPTION_TYPE = "exception_type";
    private static final String TAG_LAYER = "layer";

    /** Hard cap on cause-chain walking; chains deeper than this are vanishingly rare and not worth iterating. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** Application layers used as the {@code layer} tag value. */
    public enum Layer {
        RESOURCE("resource"),
        SERVICE("service"),
        DATABASE("database"),
        CLIENT("client"),
        LISTENER("listener"),
        PRODUCER("producer");

        static final Layer[] VALUES = values();

        private final String label;

        Layer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final MeterRegistry registry;

    /** exceptionType -> Counter[layer.ordinal()] — avoids per-call key concatenation. */
    private final ConcurrentMap<String, Counter[]> perLayerCounters = new ConcurrentHashMap<>();
    /** exceptionType -> root Counter (aggregated across layers). */
    private final ConcurrentMap<String, Counter> rootCounters = new ConcurrentHashMap<>();
    /** exceptionType -> daily AtomicLong (resets at 00:00). */
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
     * Records an exception observation. Hot-path: zero allocation after warm-up.
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

            Counter[] layerArr = perLayerCounters.get(type);
            if (layerArr == null) {
                layerArr = perLayerCounters.computeIfAbsent(type, k -> new Counter[Layer.VALUES.length]);
            }
            int idx = layer.ordinal();
            Counter perLayer = layerArr[idx];
            if (perLayer == null) {
                perLayer = Counter.builder(METRIC_PER_LAYER)
                        .description("Application exceptions broken down by root exception type and layer")
                        .tags(Tags.of(TAG_EXCEPTION_TYPE, type, TAG_LAYER, layer.label()))
                        .register(registry);
                // Benign race: if two threads create it, Micrometer returns the same underlying meter.
                layerArr[idx] = perLayer;
            }
            perLayer.increment();

            Counter root = rootCounters.get(type);
            if (root == null) {
                root = rootCounters.computeIfAbsent(type, k -> Counter.builder(METRIC_ROOT_TOTAL)
                        .description("Application exceptions aggregated across all layers by root exception type")
                        .tag(TAG_EXCEPTION_TYPE, k)
                        .register(registry));
            }
            root.increment();

            AtomicLong daily = dailyRootCounts.get(type);
            if (daily == null) {
                daily = dailyRootCounts.computeIfAbsent(type, k -> new AtomicLong());
            }
            daily.incrementAndGet();

            totalRootCount.add(1.0);
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_RECORD, "Failed to record exception metric: %s", e.getMessage());
        }
    }

    /**
     * Resolves the deepest (root) cause class name without allocating a cycle-guard set.
     * Depth-bounded to {@value #MAX_CAUSE_DEPTH} hops.
     */
    static String resolveRootCauseType(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < MAX_CAUSE_DEPTH; i++) {
            Throwable cause = cur.getCause();
            if (cause == null || cause == cur) {
                break;
            }
            cur = cause;
        }
        String n = cur.getClass().getSimpleName();
        return n.isEmpty() ? cur.getClass().getName() : n;
    }

    /**
     * Recomputes the percentage gauge rows by reading directly from the root counters.
     * Runs on a 30s schedule — the hot path never touches this work.
     */
    @Scheduled(every = "30s")
    void refreshPercentages() {
        try {
            MultiGauge g = percentageGauge;
            if (g == null) {
                return;
            }
            double total = totalRootCount.sum();
            if (total <= 0.0) {
                return;
            }
            List<MultiGauge.Row<?>> rows = new ArrayList<>(rootCounters.size());
            for (Map.Entry<String, Counter> entry : rootCounters.entrySet()) {
                double pct = (entry.getValue().count() / total) * 100.0;
                rows.add(MultiGauge.Row.of(Tags.of(Tag.of(TAG_EXCEPTION_TYPE, entry.getKey())), pct));
            }
            g.register(rows, true);
        } catch (Exception e) {
            LoggingUtil.logWarn(log, M_REFRESH, "Failed to refresh percentage gauges: %s", e.getMessage());
        }
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
     * sorted by descending count. Computed on demand — only the REST endpoint calls this.
     */
    public Map<String, ExceptionStats> snapshot() {
        double total = totalRootCount.sum();
        List<Map.Entry<String, Counter>> entries = new ArrayList<>(rootCounters.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue().count(), a.getValue().count()));

        Map<String, ExceptionStats> out = LinkedHashMap.newLinkedHashMap(entries.size());
        for (Map.Entry<String, Counter> e : entries) {
            double count = e.getValue().count();
            double pct = total > 0.0 ? (count / total) * 100.0 : 0.0;
            AtomicLong d = dailyRootCounts.get(e.getKey());
            long daily = d == null ? 0L : d.get();
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
