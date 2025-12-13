package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.QuotaThresholdConfig;
import com.csg.airtel.aaa4j.domain.repository.UserThresholdRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache for quota threshold values with TTL support.
 * Provides fast access to threshold configurations without repeated configuration lookups.
 * Supports user-wise threshold overrides from Redis cache.
 */
@ApplicationScoped
public class QuotaThresholdCache {
    private static final Logger log = Logger.getLogger(QuotaThresholdCache.class);

    private final QuotaThresholdConfig config;
    private final UserThresholdRepository userThresholdRepository;
    private final ConcurrentHashMap<String, CachedThresholds> cache;
    private final AtomicLong lastRefreshTime;

    @Inject
    public QuotaThresholdCache(QuotaThresholdConfig config, UserThresholdRepository userThresholdRepository) {
        this.config = config;
        this.userThresholdRepository = userThresholdRepository;
        this.cache = new ConcurrentHashMap<>();
        this.lastRefreshTime = new AtomicLong(0);

        // Initialize cache on startup
        refreshCache();
    }

    /**
     * Get threshold values, refreshing from config if cache is expired.
     * This returns global default thresholds.
     *
     * @return Uni with list of threshold percentages
     */
    public Uni<List<Integer>> getThresholds() {
        return Uni.createFrom().item(() -> {
            long now = System.currentTimeMillis();
            long lastRefresh = lastRefreshTime.get();
            long ttlMillis = config.cacheTtlSeconds() * 1000L;

            // Check if cache needs refresh
            if (now - lastRefresh > ttlMillis) {
                if (log.isDebugEnabled()) {
                    log.debugf("Threshold cache expired, refreshing (TTL: %d seconds)", config.cacheTtlSeconds());
                }
                refreshCache();
            }

            CachedThresholds cached = cache.get("thresholds");
            return cached != null ? cached.thresholds() : config.thresholds();
        });
    }

    /**
     * Get threshold values for a specific user and bucket from Redis cache.
     * Falls back to global defaults if user-specific thresholds are not configured.
     *
     * @param userId the user ID
     * @param bucketId the bucket/balance ID
     * @return Uni with list of threshold percentages
     */
    public Uni<List<Integer>> getUserThresholds(String userId, String bucketId) {
        return userThresholdRepository.getUserThresholdConfig(userId, bucketId)
            .onItem().transformToUni(userConfig -> {
                if (userConfig != null && userConfig.getThresholds() != null && !userConfig.getThresholds().isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debugf("Using user-specific thresholds for userId: %s, bucketId: %s, thresholds: %s",
                            userId, bucketId, userConfig.getThresholds());
                    }
                    return Uni.createFrom().item(userConfig.getThresholds());
                }
                // Fallback to global defaults
                if (log.isDebugEnabled()) {
                    log.debugf("No user-specific thresholds found for userId: %s, bucketId: %s, using global defaults",
                        userId, bucketId);
                }
                return getThresholds();
            })
            .onFailure().recoverWithUni(throwable -> {
                log.warnf(throwable, "Failed to retrieve user thresholds for userId: %s, bucketId: %s, falling back to global defaults",
                    userId, bucketId);
                return getThresholds();
            });
    }

    /**
     * Get threshold values synchronously (for non-reactive contexts).
     * This returns global default thresholds.
     *
     * @return list of threshold percentages
     */
    public List<Integer> getThresholdsSync() {
        long now = System.currentTimeMillis();
        long lastRefresh = lastRefreshTime.get();
        long ttlMillis = config.cacheTtlSeconds() * 1000L;

        // Check if cache needs refresh
        if (now - lastRefresh > ttlMillis) {
            refreshCache();
        }

        CachedThresholds cached = cache.get("thresholds");
        return cached != null ? cached.thresholds() : config.thresholds();
    }

    /**
     * Check if threshold notifications are enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Check if async execution is enabled.
     *
     * @return true if async
     */
    public boolean isAsync() {
        return config.async();
    }

    /**
     * Get async timeout duration.
     *
     * @return timeout duration
     */
    public Duration getAsyncTimeout() {
        return Duration.ofMillis(config.asyncTimeoutMs());
    }

    /**
     * Refresh cache from configuration.
     */
    private void refreshCache() {
        List<Integer> thresholds = config.thresholds();

        // Sort thresholds in descending order for efficient checking
        thresholds = thresholds.stream()
                .sorted((a, b) -> b.compareTo(a))
                .toList();

        cache.put("thresholds", new CachedThresholds(thresholds, System.currentTimeMillis()));
        lastRefreshTime.set(System.currentTimeMillis());

        if (log.isDebugEnabled()) {
            log.debugf("Threshold cache refreshed with values: %s", thresholds);
        }
    }

    /**
     * Force cache refresh (useful for testing or manual refresh).
     */
    public void forceRefresh() {
        if (log.isInfoEnabled()) {
            log.info("Forcing threshold cache refresh");
        }
        refreshCache();
    }

    /**
     * Cached threshold data with timestamp.
     */
    private record CachedThresholds(List<Integer> thresholds, long cachedAt) {
    }
}
