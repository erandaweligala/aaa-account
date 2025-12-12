package com.csg.airtel.aaa4j.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.*;
import io.quarkus.runtime.configuration.ProfileManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports metrics to JSON files for consumption by Fluent Bit or other log aggregators.
 * Runs on a scheduled interval to write current metrics snapshot.
 */
@ApplicationScoped
public class MetricsExporter {

    private static final Logger log = Logger.getLogger(MetricsExporter.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.of("UTC"));

    @Inject
    MeterRegistry registry;

    @Inject
    SystemMetricsCollector systemMetrics;

    @Inject
    MonitoringService monitoringService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String metricsFilePath;
    private boolean exportEnabled;

    public void init(String filePath, boolean enabled) {
        this.metricsFilePath = filePath;
        this.exportEnabled = enabled;

        if (exportEnabled) {
            // Ensure directory exists
            try {
                Path directory = Paths.get(filePath).getParent();
                if (directory != null && !Files.exists(directory)) {
                    Files.createDirectories(directory);
                }
                log.info("MetricsExporter initialized. Exporting to: " + filePath);
            } catch (IOException e) {
                log.error("Failed to create metrics export directory", e);
                exportEnabled = false;
            }
        } else {
            log.info("MetricsExporter disabled");
        }
    }

    /**
     * Scheduled task to export metrics every 30 seconds
     */
    @Scheduled(every = "${monitoring.metrics-export.interval:30s}",
               skipExecutionIf = MetricsExporter.ExportDisabled.class)
    public void exportMetrics() {
        if (!exportEnabled) {
            return;
        }

        try {
            ObjectNode metricsJson = buildMetricsJson();
            writeMetricsToFile(metricsJson);
        } catch (Exception e) {
            log.error("Failed to export metrics", e);
        }
    }

    private ObjectNode buildMetricsJson() {
        ObjectNode root = objectMapper.createObjectNode();

        // Add timestamp
        root.put("timestamp", TIMESTAMP_FORMATTER.format(Instant.now()));
        root.put("service", "aaa-accounting");
        root.put("environment", ProfileManager.getActiveProfile());

        // Add counters
        ObjectNode counters = objectMapper.createObjectNode();
        registry.forEachMeter(meter -> {
            if (meter instanceof Counter) {
                Counter counter = (Counter) meter;
                String name = buildMetricName(counter);
                counters.put(name, counter.count());
            }
        });
        root.set("counters", counters);

        // Add gauges
        ObjectNode gauges = objectMapper.createObjectNode();
        registry.forEachMeter(meter -> {
            if (meter instanceof Gauge) {
                Gauge gauge = (Gauge) meter;
                String name = buildMetricName(gauge);
                gauges.put(name, gauge.value());
            }
        });
        root.set("gauges", gauges);

        // Add timers (with count, mean, max)
        ObjectNode timers = objectMapper.createObjectNode();
        registry.forEachMeter(meter -> {
            if (meter instanceof Timer) {
                Timer timer = (Timer) meter;
                String baseName = buildMetricName(timer);

                ObjectNode timerData = objectMapper.createObjectNode();
                timerData.put("count", timer.count());
                timerData.put("mean_ms", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
                timerData.put("max_ms", timer.max(java.util.concurrent.TimeUnit.MILLISECONDS));
                timerData.put("total_ms", timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));

                timers.set(baseName, timerData);
            }
        });
        root.set("timers", timers);

        // Add connectivity status
        ObjectNode connectivity = objectMapper.createObjectNode();
        connectivity.put("database", monitoringService.isDatabaseConnected());
        connectivity.put("redis", monitoringService.isRedisConnected());
        connectivity.put("kafka", monitoringService.isKafkaConnected());
        connectivity.put("bng", monitoringService.isBngConnected());
        root.set("connectivity", connectivity);

        // Add system metrics
        ObjectNode system = objectMapper.createObjectNode();
        system.put("cpu_usage_percent", systemMetrics.getCurrentCpuUsage());
        system.put("memory_usage_percent", systemMetrics.getCurrentMemoryUsage());
        system.put("thread_count", systemMetrics.getCurrentThreadCount());
        system.put("has_deadlocked_threads", systemMetrics.hasDeadlockedThreads());
        root.set("system", system);

        // Add session information
        ObjectNode sessions = objectMapper.createObjectNode();
        sessions.put("active", monitoringService.getActiveSessions());
        root.set("sessions", sessions);

        return root;
    }

    private String buildMetricName(Meter meter) {
        StringBuilder name = new StringBuilder(meter.getId().getName());

        // Append tags
        for (Tag tag : meter.getId().getTags()) {
            name.append(".").append(tag.getKey()).append("_").append(tag.getValue());
        }

        return name.toString().replace(".", "_");
    }

    private void writeMetricsToFile(ObjectNode metricsJson) throws IOException {
        // Write to a temporary file first, then rename (atomic operation)
        String tempFilePath = metricsFilePath + ".tmp";

        try (FileWriter writer = new FileWriter(tempFilePath)) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer, metricsJson);
        }

        // Rename temp file to actual file (atomic on most filesystems)
        File tempFile = new File(tempFilePath);
        File actualFile = new File(metricsFilePath);

        if (!tempFile.renameTo(actualFile)) {
            // If rename fails, try copy and delete
            Files.copy(tempFile.toPath(), actualFile.toPath(),
                      java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tempFile.delete();
        }

        if (log.isDebugEnabled()) {
            log.debug("Metrics exported to " + metricsFilePath);
        }
    }

    /**
     * Export metrics on demand (can be called externally)
     */
    public void exportNow() {
        if (exportEnabled) {
            exportMetrics();
        }
    }

    /**
     * Predicate class to skip scheduled execution when export is disabled
     */
    public static class ExportDisabled implements Scheduled.SkipPredicate {
        @Inject
        MetricsExporter exporter;

        @Override
        public boolean test(Scheduled.ScheduledExecution execution) {
            return !exporter.exportEnabled;
        }
    }
}
