package com.csg.airtel.aaa4j.application.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for bucket expiration notification scheduler.
 * Configures how far ahead to check for expiring buckets and scheduler intervals.
 */
@ConfigMapping(prefix = "bucket-expiration")
public interface BucketExpirationConfig {

    /**
     * Whether the bucket expiration notification scheduler is enabled.
     * @return true if enabled, false otherwise
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Maximum number of days to look ahead for bucket expiration.
     * This should be set to the maximum DAYS_TO_EXPIRE value in your templates.
     * Default is 30 days.
     * @return maximum days to check ahead
     */
    @WithDefault("30")
    int maxDaysAhead();

    /**
     * The scheduler interval expression (cron or every expression).
     * Default runs every 1 hour.
     * @return scheduler interval expression
     */
    @WithDefault("1h")
    String schedulerInterval();
}
