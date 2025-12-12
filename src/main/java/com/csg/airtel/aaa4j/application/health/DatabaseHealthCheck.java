package com.csg.airtel.aaa4j.application.health;

import com.csg.airtel.aaa4j.domain.service.MonitoringService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.oraclelient.OraclePool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Health check for Oracle database connectivity.
 * Executes a simple query to verify the database is accessible.
 */
@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

    private static final Logger log = Logger.getLogger(DatabaseHealthCheck.class);

    @Inject
    OraclePool client;

    @Inject
    MonitoringService monitoringService;

    @Override
    public HealthCheckResponse call() {
        try {
            // Execute a simple query to verify database connectivity
            Boolean isConnected = client.query("SELECT 1 FROM DUAL")
                    .execute()
                    .map(rowSet -> rowSet.size() > 0)
                    .ifNoItem().after(Duration.ofSeconds(5)).recoverWithItem(false)
                    .onFailure().recoverWithItem(false)
                    .await().atMost(Duration.ofSeconds(6));

            monitoringService.setDatabaseConnectivity(isConnected);

            if (isConnected) {
                return HealthCheckResponse.up("Database");
            } else {
                log.warn("Database health check failed: timeout or no response");
                return HealthCheckResponse.down("Database");
            }
        } catch (Exception e) {
            log.error("Database health check failed", e);
            monitoringService.setDatabaseConnectivity(false);
            return HealthCheckResponse.down("Database");
        }
    }
}
