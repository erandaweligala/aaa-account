package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.util.StructuredLogger;


/**
 * Provides simple, efficient error logging without complex tracking or metrics collection.
 */
public final class FailoverPathLogger {

    private FailoverPathLogger() {
    }

    /**
     *
     * @param logger the logger instance
     * @param operation the operation name
     * @param sessionId the session identifier
     * @param reason the failure reason
     */
    public static void logFallbackPath(StructuredLogger logger, String operation, String sessionId, Throwable reason) {
        logger.errorf("Fallback path activated for operation [%s], sessionId [%s]: %s",
                     operation, sessionId, reason.getMessage());
    }

}
