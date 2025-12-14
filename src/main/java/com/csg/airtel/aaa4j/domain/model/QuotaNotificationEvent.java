package com.csg.airtel.aaa4j.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Kafka event for quota threshold notifications.
 */
public record QuotaNotificationEvent(
        @JsonProperty("eventTime") LocalDateTime eventTime,
        @JsonProperty("message") String message,
        @JsonProperty("username") String username,
        @JsonProperty("type") String type,
        @JsonProperty("availableQuota") Long availableQuota,
        @JsonProperty("bucketId") String bucketId,
        @JsonProperty("thresholdLevel") Long thresholdLevel,
        @JsonProperty("initialBalance") Long initialBalance,
        @JsonProperty("templateId") Long templateId
) {
}
