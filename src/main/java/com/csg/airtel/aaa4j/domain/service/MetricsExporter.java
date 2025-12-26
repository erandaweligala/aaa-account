package com.csg.airtel.aaa4j.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.*;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Service that exports metrics to JSON file for Fluent Bit consumption.
 * Exports metrics every 30 seconds to /var/log/aaa-account/metrics.json
 */
@ApplicationScoped
public class MetricsExporter {

    private static final Logger logger = LoggerFactory.getLogger(MetricsExporter.class);
    private static final String METRICS_FILE_PATH = "/var/log/aaa-account/metrics.json";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    Vertx vertx;

    /**
     * Scheduled export of metrics to JSON file.
     * Runs every 30 seconds asynchronously.
     */
    @Scheduled(every = "30s")
    public Uni<Void> exportMetrics() {
        return Uni.createFrom().item(this::collectMetrics)
                .chain(this::writeMetricsToFileAsync)
                .invoke(() -> logger.debug("Successfully exported metrics to {}", METRICS_FILE_PATH))
                .onFailure().retry().withBackOff(Duration.ofSeconds(1)).atMost(3)
                .onFailure().invoke(e -> logger.error("Failed to export metrics after retries", e))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Collect all metrics from MeterRegistry and convert to Map structure.
     */
    private Map<String, Object> collectMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("timestamp", Instant.now().toString());
        metrics.put("timestamp_millis", System.currentTimeMillis());

        Map<String, Object> gauges = new HashMap<>();
        Map<String, Object> counters = new HashMap<>();
        Map<String, Object> timers = new HashMap<>();

        for (Meter meter : meterRegistry.getMeters()) {
            String name = meter.getId().getName();
            Map<String, String> tags = new HashMap<>();
            meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));

            if (meter instanceof Gauge gauge) {
                Map<String, Object> gaugeData = new HashMap<>();
                gaugeData.put("value", gauge.value());
                gaugeData.put("tags", tags);
                gauges.put(name, gaugeData);
            } else if (meter instanceof Counter) {
                Counter counter = (Counter) meter;
                Map<String, Object> counterData = new HashMap<>();
                counterData.put("count", counter.count());
                counterData.put("tags", tags);
                counters.put(name, counterData);
            } else if (meter instanceof Timer) {
                Timer timer = (Timer) meter;
                Map<String, Object> timerData = new HashMap<>();
                timerData.put("count", timer.count());
                timerData.put("total_time_seconds", timer.totalTime(java.util.concurrent.TimeUnit.SECONDS));
                timerData.put("mean_seconds", timer.mean(java.util.concurrent.TimeUnit.SECONDS));
                timerData.put("max_seconds", timer.max(java.util.concurrent.TimeUnit.SECONDS));
                timerData.put("tags", tags);
                timers.put(name, timerData);
            }
        }

        metrics.put("gauges", gauges);
        metrics.put("counters", counters);
        metrics.put("timers", timers);

        return metrics;
    }

    /**
     * Write metrics data to JSON file asynchronously using Vertx file system.
     */
    private Uni<Void> writeMetricsToFileAsync(Map<String, Object> metricsData) {
        return Uni.createFrom().item(() -> {
            try {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metricsData);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize metrics to JSON", e);
            }
        })
        .chain(json -> ensureDirectoryExists()
                .chain(() -> vertx.fileSystem().writeFile(METRICS_FILE_PATH, Buffer.buffer(json)))
        )
        .replaceWithVoid();
    }

    /**
     * Ensure the metrics directory exists asynchronously.
     */
    private Uni<Void> ensureDirectoryExists() {
        File metricsFile = new File(METRICS_FILE_PATH);
        File parentDir = metricsFile.getParentFile();

        if (parentDir == null) {
            return Uni.createFrom().voidItem();
        }

        String parentPath = parentDir.getAbsolutePath();

        return vertx.fileSystem().exists(parentPath)
                .chain(exists -> {
                    if (exists) {
                        return Uni.createFrom().voidItem();
                    } else {
                        return vertx.fileSystem().mkdirs(parentPath)
                                .invoke(() -> logger.info("Created metrics directory: {}", parentPath))
                                .replaceWithVoid();
                    }
                });
    }
}
