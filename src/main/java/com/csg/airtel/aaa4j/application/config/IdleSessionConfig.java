package com.csg.airtel.aaa4j.application.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for idle session termination scheduler.
 * Configures timeout thresholds and scheduler intervals.
 */
@ConfigMapping(prefix = "idle-session")
public interface IdleSessionConfig {

    /**
     * Whether the idle session terminator is enabled.
     * @return true if enabled, false otherwise
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The idle session timeout threshold in minutes.
     * Sessions older than this threshold will be terminated.
     * Default is 60 minutes (1 hour).
     * @return timeout threshold in minutes
     */
    @WithDefault("60")
    int timeoutMinutes();

    /**
     * The scheduler interval expression (cron or every expression).
     * Default runs every 5 minutes.
     * @return scheduler interval expression
     */
    @WithDefault("5m")
    String schedulerInterval();

    /**
     * Batch size for processing sessions in each scheduler run.
     * @return batch size
     */
    @WithDefault("100")
    int batchSize();

}
