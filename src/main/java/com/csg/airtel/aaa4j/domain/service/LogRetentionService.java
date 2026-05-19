package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.application.config.LogRetentionConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;

/**
 * Periodically deletes rotated log files older than the configured retention
 * window. Runs on the schedule defined by {@code log-retention.cron}.
 */
@ApplicationScoped
public class LogRetentionService {

    private static final Logger log = Logger.getLogger(LogRetentionService.class);
    private static final String M_INIT = "init";
    private static final String M_CLEAN = "cleanupOldLogs";

    @Inject
    LogRetentionConfig config;

    @PostConstruct
    void init() {
        if (!config.enabled()) {
            LoggingUtil.logInfo(log, M_INIT, "Log retention scheduler disabled");
            return;
        }
        LoggingUtil.logInfo(log, M_INIT,
                "Log retention scheduler enabled. directory=%s, pattern=%s, retentionDays=%d, cron=%s",
                config.directory(), config.filePattern(), config.retentionDays(), config.cron());
    }

    @Scheduled(cron = "{log-retention.cron}")
    void cleanupOldLogs() {
        if (!config.enabled()) return;

        Path dir = Paths.get(config.directory());
        if (!Files.isDirectory(dir)) {
            LoggingUtil.logWarn(log, M_CLEAN, "Log directory does not exist: %s", dir);
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(config.retentionDays()));
        int deleted = 0;
        int failed = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, config.filePattern())) {
            for (Path file : stream) {
                try {
                    if (!Files.isRegularFile(file)) continue;
                    Instant lastModified = Files.getLastModifiedTime(file).toInstant();
                    if (lastModified.isBefore(cutoff)) {
                        Files.delete(file);
                        deleted++;
                        LoggingUtil.logInfo(log, M_CLEAN, "Deleted old log file: %s (lastModified=%s)",
                                file, lastModified);
                    }
                } catch (IOException e) {
                    failed++;
                    LoggingUtil.logWarn(log, M_CLEAN, "Failed to delete log file %s: %s", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            LoggingUtil.logError(log, M_CLEAN, e, "Failed to scan log directory %s", dir);
            return;
        }

        LoggingUtil.logInfo(log, M_CLEAN,
                "Log retention cleanup complete. retentionDays=%d, deleted=%d, failed=%d",
                config.retentionDays(), deleted, failed);
    }
}
