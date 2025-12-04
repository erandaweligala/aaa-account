package com.csg.airtel.aaa4j.domain.service;

import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class for logging failover paths and tracking failure counts across different operations.
 * This class provides centralized instrumentation for circuit breaker and retry mechanisms.
 */
public class FailoverPathLogger {

    /**
     * Logs a failover attempt with attempt and failure counts.
     *
     * @param logger the logger instance to use
     * @param path the failover path identifier (e.g., CACHE_GET, DB_QUERY, KAFKA_PUBLISH)
     * @param attemptCount the current attempt number
     * @param failureCount the total failure count so far
     * @param identifier contextual identifier (e.g., userId, sessionId)
     * @param error the error that triggered the failover
     */
    public static void logFailoverAttempt(Logger logger, String path, int attemptCount, int failureCount,
                                         String identifier, Throwable error) {
        logger.errorf("Failover: path=%s, attemptCount=%d, failureCount=%d, identifier=%s, error=%s, timestamp=%s",
                path, attemptCount, failureCount, identifier,
                error != null ? error.getMessage() : "unknown",
                LocalDateTime.now());
    }

    /**
     * Logs a failover attempt with attempt and failure counts (without error details).
     *
     * @param logger the logger instance to use
     * @param path the failover path identifier
     * @param attemptCount the current attempt number
     * @param failureCount the total failure count so far
     * @param identifier contextual identifier
     */
    public static void logFailoverAttempt(Logger logger, String path, int attemptCount, int failureCount,
                                         String identifier) {
        logger.warnf("Failover: path=%s, attemptCount=%d, failureCount=%d, identifier=%s, timestamp=%s",
                path, attemptCount, failureCount, identifier, LocalDateTime.now());
    }

    /**
     * Logs when a primary path operation starts.
     *
     * @param logger the logger instance to use
     * @param path the operation path identifier
     * @param identifier contextual identifier
     */
    public static void logPrimaryPathAttempt(Logger logger, String path, String identifier) {
        logger.debugf("Primary path attempt: path=%s, identifier=%s, timestamp=%s",
                path, identifier, LocalDateTime.now());
    }

    /**
     * Logs when switching from primary to fallback path.
     *
     * @param logger the logger instance to use
     * @param fromPath the original path that failed
     * @param toPath the fallback path being attempted
     * @param failureCount the number of failures before switching
     * @param identifier contextual identifier
     */
    public static void logPathSwitch(Logger logger, String fromPath, String toPath, int failureCount,
                                    String identifier) {
        logger.warnf("Path switch: from=%s, to=%s, failureCount=%d, identifier=%s, timestamp=%s",
                fromPath, toPath, failureCount, identifier, LocalDateTime.now());
    }

    /**
     * Logs when a circuit breaker opens.
     *
     * @param logger the logger instance to use
     * @param path the operation path where circuit breaker opened
     * @param failureCount the number of failures that triggered the circuit breaker
     */
    public static void logCircuitBreakerOpen(Logger logger, String path, int failureCount) {
        logger.errorf("Circuit breaker OPEN: path=%s, failureCount=%d, timestamp=%s",
                path, failureCount, LocalDateTime.now());
    }

    /**
     * Logs when a circuit breaker closes (recovers).
     *
     * @param logger the logger instance to use
     * @param path the operation path where circuit breaker closed
     */
    public static void logCircuitBreakerClose(Logger logger, String path) {
        logger.infof("Circuit breaker CLOSED: path=%s, timestamp=%s",
                path, LocalDateTime.now());
    }

    /**
     * Logs successful operation after failures.
     *
     * @param logger the logger instance to use
     * @param path the operation path
     * @param totalAttempts the total number of attempts including the successful one
     * @param identifier contextual identifier
     */
    public static void logSuccessAfterFailure(Logger logger, String path, int totalAttempts, String identifier) {
        logger.infof("Success after retry: path=%s, totalAttempts=%d, identifier=%s, timestamp=%s",
                path, totalAttempts, identifier, LocalDateTime.now());
    }

    /**
     * Thread-safe counter for tracking failures per operation path.
     */
    public static class FailureCounter {
        private final AtomicInteger attemptCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final String path;
        private volatile LocalDateTime lastFailureTime;

        public FailureCounter(String path) {
            this.path = path;
        }

        public int incrementAttempt() {
            return attemptCount.incrementAndGet();
        }

        public int incrementFailure() {
            lastFailureTime = LocalDateTime.now();
            return failureCount.incrementAndGet();
        }

        public int getAttemptCount() {
            return attemptCount.get();
        }

        public int getFailureCount() {
            return failureCount.get();
        }

        public String getPath() {
            return path;
        }

        public LocalDateTime getLastFailureTime() {
            return lastFailureTime;
        }

        public void reset() {
            attemptCount.set(0);
            failureCount.set(0);
            lastFailureTime = null;
        }
    }
}
