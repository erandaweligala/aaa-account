package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import org.jboss.logging.Logger;

/**
 * Provides simple, efficient error logging without complex tracking or metrics collection.
 */
public final class FailoverPathLogger {

    private static final String CLASS_NAME = "FailoverPathLogger";

    private FailoverPathLogger() {
    }

    /**
     *
     * @param logger the logger instance
     * @param operation the operation name
     * @param sessionId the session identifier
     * @param reason the failure reason
     */
    public static void logFallbackPath(Logger logger, String operation, String sessionId, Throwable reason) {
        LoggingUtil.logError(logger, CLASS_NAME, "logFallbackPath", reason, "Fallback path activated for operation [%s], sessionId [%s]: %s",
                     operation, sessionId, reason.getMessage());
    }

}
