package com.csg.airtel.aaa4j.domain.model;

import java.time.LocalDateTime;

/**
 * Record for bucket expiration information.
 * Contains minimal data needed for expiration notifications.
 */
public record ExpiringBucketInfo(
    Long serviceId,
    Long bucketId,
    String bucketUser,
    LocalDateTime expiryDate,
    Long initialBalance,
    Long currentBalance,
    String notificationTemplates
) {
}
