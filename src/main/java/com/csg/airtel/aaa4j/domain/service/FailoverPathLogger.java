package com.csg.airtel.aaa4j.domain.service;

import org.jboss.logging.Logger;

import java.util.concurrent.TimeoutException;

/**
 * Provides detailed error logging for fallback scenarios with full context and stack traces.
 */
public final class FailoverPathLogger {

    private FailoverPathLogger() {
    }

    /**
     * Logs fallback activation with detailed error information including stack traces.
     *
     * @param logger the logger instance
     * @param operation the operation name
     * @param sessionId the session identifier
     * @param reason the failure reason
     */
    public static void logFallbackPath(Logger logger, String operation, String sessionId, Throwable reason) {
        String errorType = reason.getClass().getSimpleName();
        String errorMessage = reason.getMessage() != null ? reason.getMessage() : "No error message";

        // For timeouts, provide specific guidance
        if (reason instanceof TimeoutException || errorType.contains("Timeout")) {
            logger.errorf(reason,
                "Fallback path activated for operation [%s], sessionId [%s]: TIMEOUT after configured duration. " +
                "Error: %s: %s. Consider increasing timeout values if this occurs frequently during high load.",
                operation, sessionId, errorType, errorMessage);
        } else {
            // For other errors, log with full stack trace
            logger.errorf(reason,
                "Fallback path activated for operation [%s], sessionId [%s]: %s: %s",
                operation, sessionId, errorType, errorMessage);
        }
    }

}
