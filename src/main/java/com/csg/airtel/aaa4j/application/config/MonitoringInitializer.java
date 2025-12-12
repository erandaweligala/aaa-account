package com.csg.airtel.aaa4j.application.config;

import com.csg.airtel.aaa4j.domain.service.MetricsExporter;
import com.csg.airtel.aaa4j.domain.service.MonitoringService;
import com.csg.airtel.aaa4j.domain.service.SystemMetricsCollector;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Initializes all monitoring components on application startup.
 * Sets up metrics collection, system monitoring, and metrics export.
 */
@ApplicationScoped
public class MonitoringInitializer {

    private static final Logger log = Logger.getLogger(MonitoringInitializer.class);

    @Inject
    MonitoringService monitoringService;

    @Inject
    SystemMetricsCollector systemMetricsCollector;

    @Inject
    MetricsExporter metricsExporter;

    @ConfigProperty(name = "monitoring.metrics-export.enabled", defaultValue = "true")
    boolean metricsExportEnabled;

    @ConfigProperty(name = "monitoring.metrics-export.file-path",
                    defaultValue = "/var/log/aaa-account/metrics.json")
    String metricsFilePath;

    /**
     * Initialize monitoring on application startup
     */
    void onStart(@Observes StartupEvent event) {
        log.info("=== Initializing AAA Accounting Monitoring System ===");

        try {
            // Initialize MonitoringService
            monitoringService.init();
            log.info("✓ MonitoringService initialized");

            // Initialize SystemMetricsCollector
            systemMetricsCollector.init();
            log.info("✓ SystemMetricsCollector initialized");

            // Initialize MetricsExporter
            metricsExporter.init(metricsFilePath, metricsExportEnabled);
            log.info("✓ MetricsExporter initialized" +
                    (metricsExportEnabled ? " (enabled, writing to " + metricsFilePath + ")" : " (disabled)"));

            log.info("=== Monitoring System Initialization Complete ===");
            log.info("Metrics endpoints:");
            log.info("  - Prometheus: /q/metrics");
            log.info("  - Health: /q/health");
            log.info("  - Liveness: /q/health/live");
            log.info("  - Readiness: /q/health/ready");

        } catch (Exception e) {
            log.error("Failed to initialize monitoring system", e);
            // Don't fail application startup due to monitoring issues
        }
    }
}
