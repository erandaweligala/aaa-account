package com.csg.airtel.aaa4j.application.common;

import org.jboss.logging.Logger;

public class LoggingUtil {

    public static final String TRACE_ID = "traceId";

    private LoggingUtil() {
        // Private constructor to prevent instantiation
    }

    /**
     * Log INFO level message with structured format
     */
    public static void logInfo(Logger logger,String method, String message, Object... args) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage( method, message, args));
    }

    /**
     * Log DEBUG level message with structured format
     */
    public static void logDebug(Logger logger, String method, String message, Object... args) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(method, message, args));
    }

    /**
     * Log WARN level message with structured format
     */
    public static void logWarn(Logger logger,String method, String message, Object... args) {
        logger.warn(buildMessage( method, message, args));
    }

    /**
     * Log ERROR level message with structured format and exception
     */
    public static void logError(Logger logger, String method, Throwable e, String message, Object... args) {
        String fullMessage = buildMessage( method, message, args);
        if (e != null) {
            logger.error(fullMessage, e);
        } else {
            logger.error(fullMessage);
        }
    }

    /**
     * Log TRACE level message with structured format
     */
    public static void logTrace(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(buildMessage(className, method, message, args));
    }

    /**
     * Build the structured log message in a single pass using StringBuilder.
     */
    private static String buildMessage( String method, String message, Object... args) {
        String formattedMsg = args.length > 0 ? String.format(message, args) : message;
        return '[' + method + ']' +
                formattedMsg;
    }

}
