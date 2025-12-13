package com.csg.airtel.aaa4j.domain.repository;

import com.csg.airtel.aaa4j.domain.model.UserThresholdConfig;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

import java.time.Duration;
import java.util.List;

/**
 * Repository for managing user-specific threshold configurations in Redis.
 * Provides methods to store, retrieve, and manage user-wise threshold overrides.
 */
@ApplicationScoped
@JBossLog
public class UserThresholdRepository {

    private static final String KEY_PREFIX = "user:threshold:";
    private static final String DEFAULT_TTL_SECONDS = "300"; // 5 minutes default

    private final ReactiveValueCommands<String, UserThresholdConfig> valueCommands;

    @Inject
    public UserThresholdRepository(ReactiveRedisDataSource reactiveRedisDataSource) {
        this.valueCommands = reactiveRedisDataSource.value(UserThresholdConfig.class);
    }

    /**
     * Build Redis key for user threshold config
     */
    private String buildKey(String userId, String bucketId) {
        if (bucketId != null && !bucketId.isEmpty()) {
            return KEY_PREFIX + userId + ":" + bucketId;
        }
        return KEY_PREFIX + userId + ":default";
    }

    /**
     * Store user-specific threshold configuration in Redis
     */
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<Void> saveUserThresholdConfig(UserThresholdConfig config) {
        final long startTime = log.isDebugEnabled() ? System.currentTimeMillis() : 0;

        if (log.isDebugEnabled()) {
            log.debugf("Storing user threshold config for userId: %s, bucketId: %s",
                config.getUserId(), config.getBucketId());
        }

        String key = buildKey(config.getUserId(), config.getBucketId());

        // Use configured TTL or default
        int ttlSeconds = config.getCacheTtlSeconds() != null ?
            config.getCacheTtlSeconds() : Integer.parseInt(DEFAULT_TTL_SECONDS);

        return valueCommands.setex(key, ttlSeconds, config)
            .replaceWithVoid()
            .onItem().invoke(() -> {
                if (log.isDebugEnabled()) {
                    log.debugf("User threshold config stored for userId: %s, bucketId: %s in %d ms",
                        config.getUserId(), config.getBucketId(),
                        (System.currentTimeMillis() - startTime));
                }
            })
            .onFailure().invoke(e ->
                log.errorf(e, "Failed to store user threshold config for userId: %s, bucketId: %s",
                    config.getUserId(), config.getBucketId())
            );
    }

    /**
     * Get user-specific threshold configuration from Redis
     */
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<UserThresholdConfig> getUserThresholdConfig(String userId, String bucketId) {
        final long startTime = log.isDebugEnabled() ? System.currentTimeMillis() : 0;

        if (log.isDebugEnabled()) {
            log.debugf("Retrieving user threshold config for userId: %s, bucketId: %s",
                userId, bucketId);
        }

        String key = buildKey(userId, bucketId);

        return valueCommands.get(key)
            .onItem().transform(config -> {
                if (log.isDebugEnabled()) {
                    if (config != null) {
                        log.debugf("User threshold config retrieved for userId: %s, bucketId: %s in %d ms",
                            userId, bucketId, (System.currentTimeMillis() - startTime));
                    } else {
                        log.debugf("No user threshold config found for userId: %s, bucketId: %s",
                            userId, bucketId);
                    }
                }
                return config;
            })
            .onFailure().invoke(e ->
                log.errorf(e, "Failed to retrieve user threshold config for userId: %s, bucketId: %s",
                    userId, bucketId)
            );
    }

    /**
     * Check if a specific threshold has been notified for a user/bucket
     */
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 3000)
    public Uni<Boolean> isThresholdNotified(String userId, String bucketId, int threshold) {
        return getUserThresholdConfig(userId, bucketId)
            .onItem().transform(config -> {
                if (config != null) {
                    return config.isThresholdNotified(threshold);
                }
                return false;
            })
            .onFailure().recoverWithItem(false);
    }

    /**
     * Mark a threshold as notified for a user/bucket
     */
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<Void> markThresholdNotified(String userId, String bucketId, int threshold) {
        return getUserThresholdConfig(userId, bucketId)
            .onItem().transformToUni(config -> {
                if (config == null) {
                    // Create new config if doesn't exist
                    config = UserThresholdConfig.builder()
                        .userId(userId)
                        .bucketId(bucketId)
                        .enabled(true)
                        .build();
                }

                config.markThresholdNotified(threshold);
                return saveUserThresholdConfig(config);
            })
            .onFailure().invoke(e ->
                log.errorf(e, "Failed to mark threshold notified for userId: %s, bucketId: %s, threshold: %d",
                    userId, bucketId, threshold)
            );
    }

    /**
     * Reset notification state for a specific threshold
     */
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<Void> resetThresholdNotification(String userId, String bucketId, int threshold) {
        return getUserThresholdConfig(userId, bucketId)
            .onItem().transformToUni(config -> {
                if (config != null) {
                    config.resetThresholdNotification(threshold);
                    return saveUserThresholdConfig(config);
                }
                return Uni.createFrom().voidItem();
            })
            .onFailure().invoke(e ->
                log.errorf(e, "Failed to reset threshold notification for userId: %s, bucketId: %s, threshold: %d",
                    userId, bucketId, threshold)
            );
    }

    /**
     * Reset all notification states for a user/bucket
     */
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<Void> resetAllNotifications(String userId, String bucketId) {
        return getUserThresholdConfig(userId, bucketId)
            .onItem().transformToUni(config -> {
                if (config != null) {
                    config.resetAllNotifications();
                    return saveUserThresholdConfig(config);
                }
                return Uni.createFrom().voidItem();
            })
            .onFailure().invoke(e ->
                log.errorf(e, "Failed to reset all notifications for userId: %s, bucketId: %s",
                    userId, bucketId)
            );
    }

    /**
     * Delete user threshold configuration
     */
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<Void> deleteUserThresholdConfig(String userId, String bucketId) {
        final long startTime = log.isDebugEnabled() ? System.currentTimeMillis() : 0;

        if (log.isDebugEnabled()) {
            log.debugf("Deleting user threshold config for userId: %s, bucketId: %s",
                userId, bucketId);
        }

        String key = buildKey(userId, bucketId);

        return valueCommands.getdel(key)
            .replaceWithVoid()
            .onItem().invoke(() -> {
                if (log.isDebugEnabled()) {
                    log.debugf("User threshold config deleted for userId: %s, bucketId: %s in %d ms",
                        userId, bucketId, (System.currentTimeMillis() - startTime));
                }
            })
            .onFailure().invoke(e ->
                log.errorf(e, "Failed to delete user threshold config for userId: %s, bucketId: %s",
                    userId, bucketId)
            );
    }

    /**
     * Update custom thresholds for a user/bucket
     */
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<Void> updateUserThresholds(String userId, String bucketId, List<Integer> thresholds) {
        return getUserThresholdConfig(userId, bucketId)
            .onItem().transformToUni(config -> {
                if (config == null) {
                    // Create new config
                    config = UserThresholdConfig.builder()
                        .userId(userId)
                        .bucketId(bucketId)
                        .thresholds(thresholds)
                        .enabled(true)
                        .build();
                } else {
                    // Update existing config
                    config.setThresholds(thresholds);
                    config.setLastUpdated(System.currentTimeMillis());
                }
                return saveUserThresholdConfig(config);
            })
            .onFailure().invoke(e ->
                log.errorf(e, "Failed to update user thresholds for userId: %s, bucketId: %s",
                    userId, bucketId)
            );
    }
}
