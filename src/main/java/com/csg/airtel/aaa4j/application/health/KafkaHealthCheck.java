package com.csg.airtel.aaa4j.application.health;

import io.smallrye.reactive.messaging.kafka.KafkaClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Health check for Kafka connectivity and cluster status.
 * This check ensures Kafka brokers are available and the cluster is healthy.
 */
@Readiness
@ApplicationScoped
public class KafkaHealthCheck implements HealthCheck {

    private static final Logger logger = LoggerFactory.getLogger(KafkaHealthCheck.class);
    private static final long TIMEOUT_SECONDS = 5;

    @Inject
    KafkaClientService kafkaClientService;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("Kafka connection health check");

        AdminClient adminClient = null;
        try {
            // Get Kafka AdminClient from KafkaClientService
            adminClient = kafkaClientService.getAdmin();

            if (adminClient == null) {
                return builder
                        .down()
                        .withData("status", "Kafka AdminClient is not available")
                        .build();
            }

            // Describe cluster to check connectivity
            DescribeClusterResult clusterResult = adminClient.describeCluster();
            String clusterId = clusterResult.clusterId().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int nodeCount = clusterResult.nodes().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size();

            return builder
                    .up()
                    .withData("status", "Kafka cluster is healthy")
                    .withData("cluster_id", clusterId)
                    .withData("node_count", nodeCount)
                    .build();

        } catch (Exception e) {
            logger.error("Kafka health check failed", e);
            return builder
                    .down()
                    .withData("status", "Kafka connection failed")
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
