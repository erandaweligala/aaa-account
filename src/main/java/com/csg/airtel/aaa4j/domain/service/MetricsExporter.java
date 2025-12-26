package com.csg.airtel.aaa4j.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.*;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Scheduled export of metrics to JSON file.
     * Runs every 30 seconds.
     */
    @Scheduled(every = "30s")
    public void exportMetrics() {
        try {
            Map<String, Object> metricsData = collectMetrics();
            writeMetricsToFile(metricsData);
            logger.debug("Successfully exported metrics to {}", METRICS_FILE_PATH);
        } catch (Exception e) {
            logger.error("Failed to export metrics", e);
        }
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

            if (meter instanceof Gauge) {
                Gauge gauge = (Gauge) meter;
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
     * Write metrics data to JSON file.
     */
    private void writeMetricsToFile(Map<String, Object> metricsData) throws IOException {
        File metricsFile = new File(METRICS_FILE_PATH);
        File parentDir = metricsFile.getParentFile();

        // Ensure directory exists
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (created) {
                logger.info("Created metrics directory: {}", parentDir.getAbsolutePath());
            }
        }

        // Write metrics to file
        try (FileWriter writer = new FileWriter(metricsFile)) {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metricsData);
            writer.write(json);
            writer.flush();
        }
    }
}
