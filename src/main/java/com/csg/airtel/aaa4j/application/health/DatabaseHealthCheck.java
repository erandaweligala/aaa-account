package com.csg.airtel.aaa4j.application.health;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.oracledbclient.OraclePool;
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
 * Health check for Oracle Database connectivity and responsiveness.
 * This check ensures the database connection pool is healthy and can execute queries.
 */
@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseHealthCheck.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Inject
    OraclePool client;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("Database connection health check");

        try {
            // Execute simple query to verify database connectivity
            String result = client.query("SELECT 'OK' FROM DUAL")
                    .execute()
                    .map(rowSet -> {
                        if (rowSet.iterator().hasNext()) {
                            return rowSet.iterator().next().getString(0);
                        }
                        return "EMPTY";
                    })
                    .await()
                    .atMost(TIMEOUT);

            if ("OK".equals(result)) {
                return builder
                        .up()
                        .withData("status", "Database is responding")
                        .withData("pool_size", client.size())
                        .build();
            } else {
                return builder
                        .down()
                        .withData("status", "Database returned unexpected response")
                        .withData("response", result)
                        .build();
            }
        } catch (Exception e) {
            logger.error("Database health check failed", e);
            return builder
                    .down()
                    .withData("status", "Database connection failed")
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
