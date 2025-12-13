package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.QuotaThresholdConfig;
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
 */
//todo need to implement to get redis cache  userVise change to the threshold
@ApplicationScoped
public class QuotaThresholdCache {
    private static final Logger log = Logger.getLogger(QuotaThresholdCache.class);

    private final QuotaThresholdConfig config;
    private final ConcurrentHashMap<String, CachedThresholds> cache;
    private final AtomicLong lastRefreshTime;

    @Inject
    public QuotaThresholdCache(QuotaThresholdConfig config) {
        this.config = config;
        this.cache = new ConcurrentHashMap<>();
        this.lastRefreshTime = new AtomicLong(0);

        // Initialize cache on startup
        refreshCache();
    }

    /**
     * Get threshold values, refreshing from config if cache is expired.
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
     * Get threshold values synchronously (for non-reactive contexts).
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
