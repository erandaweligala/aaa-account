package com.csg.airtel.aaa4j.application.health;

import com.csg.airtel.aaa4j.domain.service.MonitoringService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Health check for Redis connectivity.
 * Executes a PING command to verify Redis is accessible.
 */
@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck {

    private static final Logger log = Logger.getLogger(RedisHealthCheck.class);

    @Inject
    ReactiveRedisDataSource redisDataSource;

    @Inject
    MonitoringService monitoringService;

    @Override
    public HealthCheckResponse call() {
        try {
            // Execute PING command to verify Redis connectivity
            Boolean isConnected = redisDataSource.execute("PING")
                    .map(response -> response != null && "PONG".equals(response.toString()))
                    .ifNoItem().after(Duration.ofSeconds(3)).recoverWithItem(false)
                    .onFailure().recoverWithItem(false)
                    .await().atMost(Duration.ofSeconds(4));

            monitoringService.setRedisConnectivity(isConnected);

            if (isConnected) {
                return HealthCheckResponse.up("Redis");
            } else {
                log.warn("Redis health check failed: timeout or no response");
                return HealthCheckResponse.down("Redis");
            }
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            monitoringService.setRedisConnectivity(false);
            return HealthCheckResponse.down("Redis");
        }
    }
}
