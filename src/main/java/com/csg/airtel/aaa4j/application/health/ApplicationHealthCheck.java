package com.csg.airtel.aaa4j.application.health;

import com.csg.airtel.aaa4j.domain.service.MonitoringService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

/**
 * Overall application health check.
 * Provides comprehensive status including session count and connectivity.
 */
@Liveness
@ApplicationScoped
public class ApplicationHealthCheck implements HealthCheck {

    @Inject
    MonitoringService monitoringService;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("AAA Accounting Service");

        try {
            // Add session information
            builder.withData("active_sessions", monitoringService.getActiveSessions());

            // Add connectivity status
            builder.withData("database_connected", monitoringService.isDatabaseConnected());
            builder.withData("redis_connected", monitoringService.isRedisConnected());
            builder.withData("kafka_connected", monitoringService.isKafkaConnected());
            builder.withData("bng_connected", monitoringService.isBngConnected());

            // Service is UP if all critical components are connected
            boolean isHealthy = monitoringService.isDatabaseConnected()
                    && monitoringService.isRedisConnected()
                    && monitoringService.isKafkaConnected();

            if (isHealthy) {
                builder.up();
            } else {
                builder.down();
            }

        } catch (Exception e) {
            builder.down().withData("error", e.getMessage());
        }

        return builder.build();
    }
}
