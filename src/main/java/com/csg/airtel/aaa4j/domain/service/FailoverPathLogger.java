package com.csg.airtel.aaa4j.domain.service;

import org.jboss.logging.Logger;

/**
 * Lightweight utility for logging circuit breaker fallback paths with minimal overhead.
 * Provides simple, efficient error logging without complex tracking or metrics collection.
 */
public final class FailoverPathLogger {

    private FailoverPathLogger() {
        // Utility class - prevent instantiation
    }

    /**
     * Logs circuit breaker opening with minimal overhead.
     *
     * @param logger the logger instance
     * @param operation the operation name
     * @param reason the failure reason
     */
    public static void logCircuitBreakerOpen(Logger logger, String operation, Throwable reason) {
        logger.errorf("Circuit breaker OPEN for operation [%s]: %s", operation, reason.getMessage());
    }

    /**
     * Logs fallback path activation with minimal overhead.
     *
     * @param logger the logger instance
     * @param operation the operation name
     * @param sessionId the session identifier
     * @param reason the failure reason
     */
    public static void logFallbackPath(Logger logger, String operation, String sessionId, Throwable reason) {
        logger.errorf("Fallback path activated for operation [%s], sessionId [%s]: %s",
                     operation, sessionId, reason.getMessage());
    }

    /**
     * Logs fallback path activation without session context.
     *
     * @param logger the logger instance
     * @param operation the operation name
     * @param reason the failure reason
     */
    public static void logFallbackPath(Logger logger, String operation, Throwable reason) {
        logger.errorf("Fallback path activated for operation [%s]: %s",
                     operation, reason.getMessage());
    }

    /**
     * Logs retry attempt with minimal overhead.
     *
     * @param logger the logger instance
     * @param operation the operation name
     * @param attemptNumber the retry attempt number
     */
    public static void logRetryAttempt(Logger logger, String operation, int attemptNumber) {
        logger.warnf("Retry attempt %d for operation [%s]", attemptNumber, operation);
    }

    /**
     * Logs successful recovery after failure.
     *
     * @param logger the logger instance
     * @param operation the operation name
     */
    public static void logSuccessAfterRetry(Logger logger, String operation) {
        logger.infof("Operation [%s] succeeded after retry", operation);
    }

    /**
     * Logs circuit breaker closing (recovery).
     *
     * @param logger the logger instance
     * @param operation the operation name
     */
    public static void logCircuitBreakerClosed(Logger logger, String operation) {
        logger.infof("Circuit breaker CLOSED for operation [%s] - service recovered", operation);
    }
}
