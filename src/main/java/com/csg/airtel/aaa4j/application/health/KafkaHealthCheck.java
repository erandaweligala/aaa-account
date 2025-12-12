package com.csg.airtel.aaa4j.application.health;

import com.csg.airtel.aaa4j.domain.service.MonitoringService;
import io.smallrye.reactive.messaging.kafka.KafkaConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Health check for Kafka connectivity.
 * Verifies that Kafka channels are operational.
 */
@Readiness
@ApplicationScoped
public class KafkaHealthCheck implements HealthCheck {

    private static final Logger log = Logger.getLogger(KafkaHealthCheck.class);

    @Inject
    MonitoringService monitoringService;

    // Note: Kafka health is primarily managed through SmallRye Reactive Messaging
    // This provides an additional layer of monitoring

    @Override
    public HealthCheckResponse call() {
        try {
            // Check if Kafka connectivity status from monitoring service
            // This is updated by the actual Kafka operations
            boolean isConnected = monitoringService.isKafkaConnected();

            if (isConnected) {
                return HealthCheckResponse.up("Kafka");
            } else {
                log.warn("Kafka health check: connectivity issues detected");
                return HealthCheckResponse.down("Kafka");
            }
        } catch (Exception e) {
            log.error("Kafka health check failed", e);
            monitoringService.setKafkaConnectivity(false);
            return HealthCheckResponse.down("Kafka");
        }
    }
}
