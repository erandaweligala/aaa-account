package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.repository.UserThresholdRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * Helper service for managing user-specific threshold notification states.
 * Provides a clean interface for checking and marking threshold notifications
 * using Redis cache for persistence across application restarts.
 */
@ApplicationScoped
@JBossLog
public class UserThresholdNotificationHelper {

    private final UserThresholdRepository userThresholdRepository;

    @Inject
    public UserThresholdNotificationHelper(UserThresholdRepository userThresholdRepository) {
        this.userThresholdRepository = userThresholdRepository;
    }

    /**
     * Check if a threshold has been notified for a specific user and bucket.
     * This checks Redis cache for the notification state.
     *
     * @param userId the user ID
     * @param bucketId the bucket/balance ID
     * @param threshold the threshold percentage
     * @return Uni with true if notified, false otherwise
     */
    public Uni<Boolean> isThresholdNotified(String userId, String bucketId, int threshold) {
        return userThresholdRepository.isThresholdNotified(userId, bucketId, threshold);
    }

    /**
     * Mark a threshold as notified for a specific user and bucket.
     * This updates the notification state in Redis cache.
     *
     * @param userId the user ID
     * @param bucketId the bucket/balance ID
     * @param threshold the threshold percentage
     * @return Uni signaling completion
     */
    public Uni<Void> markThresholdNotified(String userId, String bucketId, int threshold) {
        if (log.isDebugEnabled()) {
            log.debugf("Marking threshold %d as notified for userId: %s, bucketId: %s",
                threshold, userId, bucketId);
        }
        return userThresholdRepository.markThresholdNotified(userId, bucketId, threshold);
    }

    /**
     * Reset notification state for a specific threshold.
     * Useful when quota is refilled and notifications should be sent again.
     *
     * @param userId the user ID
     * @param bucketId the bucket/balance ID
     * @param threshold the threshold percentage
     * @return Uni signaling completion
     */
    public Uni<Void> resetThresholdNotification(String userId, String bucketId, int threshold) {
        if (log.isDebugEnabled()) {
            log.debugf("Resetting threshold %d notification for userId: %s, bucketId: %s",
                threshold, userId, bucketId);
        }
        return userThresholdRepository.resetThresholdNotification(userId, bucketId, threshold);
    }

    /**
     * Reset all notification states for a user/bucket.
     * Useful when quota is refilled to maximum and all thresholds should trigger again.
     *
     * @param userId the user ID
     * @param bucketId the bucket/balance ID
     * @return Uni signaling completion
     */
    public Uni<Void> resetAllNotifications(String userId, String bucketId) {
        if (log.isDebugEnabled()) {
            log.debugf("Resetting all notifications for userId: %s, bucketId: %s", userId, bucketId);
        }
        return userThresholdRepository.resetAllNotifications(userId, bucketId);
    }

    /**
     * Update custom thresholds for a specific user and bucket.
     * This creates or updates the user-specific threshold configuration in Redis.
     *
     * @param userId the user ID
     * @param bucketId the bucket/balance ID
     * @param thresholds the custom threshold values
     * @return Uni signaling completion
     */
    public Uni<Void> updateUserThresholds(String userId, String bucketId, List<Integer> thresholds) {
        if (log.isInfoEnabled()) {
            log.infof("Updating custom thresholds for userId: %s, bucketId: %s, thresholds: %s",
                userId, bucketId, thresholds);
        }
        return userThresholdRepository.updateUserThresholds(userId, bucketId, thresholds);
    }

    /**
     * Delete user threshold configuration from Redis.
     * This removes both custom thresholds and notification states.
     *
     * @param userId the user ID
     * @param bucketId the bucket/balance ID
     * @return Uni signaling completion
     */
    public Uni<Void> deleteUserThresholdConfig(String userId, String bucketId) {
        if (log.isInfoEnabled()) {
            log.infof("Deleting user threshold config for userId: %s, bucketId: %s", userId, bucketId);
        }
        return userThresholdRepository.deleteUserThresholdConfig(userId, bucketId);
    }
}
