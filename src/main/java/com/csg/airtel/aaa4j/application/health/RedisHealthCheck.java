package com.csg.airtel.aaa4j.application.health;

import io.quarkus.redis.datasource.RedisDataSource;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Health check for Redis connectivity and responsiveness.
 * This check ensures Redis is available and responding to commands.
 */
@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck {

    private static final Logger logger = LoggerFactory.getLogger(RedisHealthCheck.class);
    private static final String HEALTH_CHECK_KEY = "health:check:ping";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Inject
    RedisDataSource redisDataSource;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("Redis connection health check");

        try {
            // Perform PING command to check Redis connectivity
            String response = redisDataSource.execute("PING").toString();

            if ("PONG".equals(response)) {
                return builder
                        .up()
                        .withData("status", "Redis is responding")
                        .withData("response", response)
                        .build();
            } else {
                return builder
                        .down()
                        .withData("status", "Redis returned unexpected response")
                        .withData("response", response)
                        .build();
            }
        } catch (Exception e) {
            logger.error("Redis health check failed", e);
            return builder
                    .down()
                    .withData("status", "Redis connection failed")
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
