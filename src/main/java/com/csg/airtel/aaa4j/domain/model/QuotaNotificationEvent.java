package com.csg.airtel.aaa4j.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Kafka event for quota threshold notifications.
 * Published when user's data quota exceeds configured thresholds (e.g., 50%, 80%, 100%).
 */
public record QuotaNotificationEvent(
        @JsonProperty("eventTime") LocalDateTime eventTime,
        @JsonProperty("message") String message,
        @JsonProperty("username") String username,
        @JsonProperty("type") String type,
        @JsonProperty("availableQuota") Long availableQuota,
        @JsonProperty("bucketId") String bucketId,
        @JsonProperty("thresholdLevel") Long thresholdLevel,
        @JsonProperty("initialBalance") Long initialBalance
) {
}
