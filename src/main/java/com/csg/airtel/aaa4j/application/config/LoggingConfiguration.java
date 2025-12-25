package com.csg.airtel.aaa4j.application.config;

import com.csg.airtel.aaa4j.domain.util.StructuredLogger;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Logging performance configuration for high-TPS AAA/RADIUS systems.
 *
 * Configures adaptive log sampling to reduce logging overhead at 2500+ TPS.
 * Without sampling: 2500 TPS × 7 pods = 17,500 logs/sec = 7-9 MB/sec
 * With 10% sampling: ~1,750 logs/sec = 0.7-0.9 MB/sec (10x reduction)
 */
@ApplicationScoped
public class LoggingConfiguration {

    @ConfigProperty(name = "logging.sampling.enabled", defaultValue = "false")
    boolean samplingEnabled;

    @ConfigProperty(name = "logging.sampling.rate", defaultValue = "10")
    int samplingRate;

    private static final StructuredLogger log = StructuredLogger.getLogger(LoggingConfiguration.class);

    /**
     * Initialize logging configuration at application startup.
     *
     * Configuration via environment variables:
     * - LOGGING_SAMPLING_ENABLED=true   (enable sampling)
     * - LOGGING_SAMPLING_RATE=10        (log 1 in 10 requests = 10%)
     */
    void onStart(@Observes StartupEvent ev) {
        StructuredLogger.configureSampling(samplingEnabled, samplingRate);

        if (samplingEnabled) {
            log.infoForced("Logging sampling configured", StructuredLogger.Fields.create(3)
                    .add("enabled", true)
                    .add("rate", samplingRate)
                    .add("effectivePercentage", String.format("%.1f%%", (100.0 / samplingRate)))
                    .build());
        } else {
            log.infoForced("Logging sampling disabled - full logging enabled",
                    StructuredLogger.Fields.create(1)
                    .add("enabled", false)
                    .build());
        }
    }
}
