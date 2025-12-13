package com.csg.airtel.aaa4j.application.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;

/**
 * Configuration for dynamic quota threshold notifications.
 * Supports multiple configurable threshold levels with caching.
 */
@ConfigMapping(prefix = "quota-threshold")
public interface QuotaThresholdConfig {

    /**
     * Whether quota threshold notifications are enabled.
     * @return true if enabled, false otherwise
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Cache TTL (time-to-live) for threshold values in seconds.
     * Cached thresholds improve performance for high-frequency checks.
     * Default is 300 seconds (5 minutes).
     * @return cache TTL in seconds
     */
    @WithDefault("300")
    int cacheTtlSeconds();

    /**
     * List of threshold percentages to monitor (e.g., 60, 70, 80).
     * These represent the percentage of quota remaining when notifications are triggered.
     * Default thresholds are 60%, 70%, and 80%.
     * @return list of threshold percentages
     */
    @WithDefault("60,70,80")
    List<Integer> thresholds();

    /**
     * Whether to run threshold checks asynchronously.
     * Async execution improves performance by not blocking the main request thread.
     * Default is true.
     * @return true if async, false otherwise
     */
    @WithDefault("true")
    boolean async();

    /**
     * Timeout for async threshold check operations in milliseconds.
     * Default is 5000ms (5 seconds).
     * @return timeout in milliseconds
     */
    @WithDefault("5000")
    long asyncTimeoutMs();
}
