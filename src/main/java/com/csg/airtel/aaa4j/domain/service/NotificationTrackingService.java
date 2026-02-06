package com.csg.airtel.aaa4j.domain.service;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;

@ApplicationScoped
public class NotificationTrackingService {

    private static final Logger LOG = Logger.getLogger(NotificationTrackingService.class);
    private static final String TRACKING_KEY_PREFIX = "notification-sent:";

    // Default time window to prevent duplicate notifications (1 hour)
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final ReactiveValueCommands<String, String> valueCommands;

    @Inject
    public NotificationTrackingService(ReactiveRedisDataSource reactiveRedisDataSource) {
        this.valueCommands = reactiveRedisDataSource.value(String.class, String.class);
    }

    /**
     * Check if a notification with the same parameters was already sent within the time window.
     *
     * @param username the username
     * @param templateId the template ID
     * @param bucketId the bucket ID
     * @param thresholdLevel the threshold level
     * @return Uni<Boolean> true if already sent (duplicate), false if not sent (should send)
     */
    public Uni<Boolean> isDuplicateNotification(
            String username,
            Long templateId,
            String bucketId,
            Long thresholdLevel) {

        String trackingKey = buildTrackingKey(username, templateId, bucketId, thresholdLevel);

        return valueCommands.get(trackingKey)
                .onItem().transform(value -> {
                    boolean isDuplicate = value != null;
                    if (isDuplicate) {
                        LOG.infof("Duplicate notification detected for key: %s", trackingKey);
                    }
                    return isDuplicate;
                })
                .onFailure().invoke(error ->
                        LOG.warnf("Error checking duplicate notification for key %s: %s. Allowing notification to proceed.",
                                trackingKey, error.getMessage()))
                .onFailure().recoverWithItem(false); // On error, allow notification (fail-open)
    }

    /**
     *
     * @param username the username
     * @param templateId the template ID
     * @param bucketId the bucket ID
     * @param thresholdLevel the threshold level
     * @return Uni<Void> completes when tracking is recorded
     */
    public Uni<Void> markNotificationSent(
            String username,
            Long templateId,
            String bucketId,
            Long thresholdLevel) {

        return markNotificationSent(username, templateId, bucketId, thresholdLevel, DEFAULT_TTL);
    }

    /**
     * Mark a notification as sent with custom TTL.
     *
     * @param username the username
     * @param templateId the template ID
     * @param bucketId the bucket ID
     * @param thresholdLevel the threshold level
     * @param ttl time-to-live duration for the tracking entry
     * @return Uni<Void> completes when tracking is recorded
     */
    public Uni<Void> markNotificationSent(
            String username,
            Long templateId,
            String bucketId,
            Long thresholdLevel,
            Duration ttl) {

        String trackingKey = buildTrackingKey(username, templateId, bucketId, thresholdLevel);
        String timestamp = String.valueOf(System.currentTimeMillis());

        return valueCommands.setex(trackingKey, ttl.getSeconds(), timestamp)
                .onItem().invoke(success ->
                        LOG.debugf("Marked notification as sent: %s (TTL: %d seconds)",
                                trackingKey, ttl.getSeconds()))
                .onFailure().invoke(error ->
                        LOG.warnf("Failed to mark notification as sent for key %s: %s",
                                trackingKey, error.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Build a unique tracking key for a notification.
     * Format: notification-sent:{username}:{templateId}:{bucketId}:{thresholdLevel}
     */
    private String buildTrackingKey(String username, Long templateId, String bucketId, Long thresholdLevel) {
        return String.format("%s%s:%d:%s:%d",
                TRACKING_KEY_PREFIX,
                username,
                templateId,
                bucketId,
                thresholdLevel);
    }

    /**
     * Clear a notification tracking entry (for testing or manual intervention).
     *
     * @param username the username
     * @param templateId the template ID
     * @param bucketId the bucket ID
     * @param thresholdLevel the threshold level
     * @return Uni<Void> completes when entry is cleared
     */
    public Uni<Void> clearNotificationTracking(
            String username,
            Long templateId,
            String bucketId,
            Long thresholdLevel) {

        String trackingKey = buildTrackingKey(username, templateId, bucketId, thresholdLevel);

        return valueCommands.getdel(trackingKey)
                .onItem().invoke(deleted ->
                        LOG.infof("Cleared notification tracking for key: %s", trackingKey))
                .onFailure().invoke(error ->
                        LOG.warnf("Failed to clear notification tracking for key %s: %s",
                                trackingKey, error.getMessage()))
                .replaceWithVoid();
    }
}
