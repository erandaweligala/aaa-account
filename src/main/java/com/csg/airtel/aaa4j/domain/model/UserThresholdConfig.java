package com.csg.airtel.aaa4j.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User-specific threshold configuration stored in Redis cache.
 * Allows per-user and per-bucket threshold overrides.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserThresholdConfig {

    /**
     * Username/userId
     */
    private String userId;

    /**
     * Bucket/balance ID (optional - if null, applies to all buckets for this user)
     */
    private String bucketId;

    /**
     * Custom threshold values (e.g., [60, 70, 80])
     * If null, falls back to global defaults
     */
    private List<Integer> thresholds;

    /**
     * Whether threshold notifications are enabled for this user/bucket
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Cache TTL in seconds (defaults to global config if not set)
     */
    private Integer cacheTtlSeconds;

    /**
     * Whether to use async processing (defaults to global config if not set)
     */
    private Boolean async;

    /**
     * Async timeout in milliseconds (defaults to global config if not set)
     */
    private Integer asyncTimeoutMs;

    /**
     * Track which thresholds have been notified for this user/bucket
     * Key: threshold value (e.g., "60"), Value: notified flag
     */
    @Builder.Default
    private Map<String, Boolean> notifiedThresholds = new HashMap<>();

    /**
     * Timestamp when this config was last updated
     */
    @Builder.Default
    private long lastUpdated = System.currentTimeMillis();

    /**
     * Check if a specific threshold has been notified
     */
    public boolean isThresholdNotified(int threshold) {
        return notifiedThresholds.getOrDefault(String.valueOf(threshold), false);
    }

    /**
     * Mark a threshold as notified
     */
    public void markThresholdNotified(int threshold) {
        notifiedThresholds.put(String.valueOf(threshold), true);
        lastUpdated = System.currentTimeMillis();
    }

    /**
     * Reset notification state for a threshold
     */
    public void resetThresholdNotification(int threshold) {
        notifiedThresholds.put(String.valueOf(threshold), false);
        lastUpdated = System.currentTimeMillis();
    }

    /**
     * Reset all notification states
     */
    public void resetAllNotifications() {
        notifiedThresholds.clear();
        lastUpdated = System.currentTimeMillis();
    }
}
