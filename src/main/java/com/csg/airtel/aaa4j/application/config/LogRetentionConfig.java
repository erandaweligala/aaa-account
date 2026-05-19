package com.csg.airtel.aaa4j.application.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for the log retention cleanup scheduler.
 * Files in the log directory older than the configured retention window
 * are deleted on each scheduler run.
 */
@ConfigMapping(prefix = "log-retention")
public interface LogRetentionConfig {

    @WithDefault("true")
    boolean enabled();

    /**
     * Directory containing rotated log files to scan for cleanup.
     */
    @WithDefault("/var/log/dte")
    String directory();

    /**
     * Filename glob matched against files in the directory.
     * Only matching files are eligible for deletion.
     */
    @WithDefault("accounting-service-*.log*")
    String filePattern();

    /**
     * Retention window in days. Files whose last-modified time is older
     * than this many days are deleted.
     */
    @WithDefault("7")
    int retentionDays();

    /**
     * Cron expression for the cleanup scheduler. Default runs daily at 02:00.
     */
    @WithDefault("0 0 2 * * ?")
    String cron();
}
