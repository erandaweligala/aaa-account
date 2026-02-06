package com.csg.airtel.aaa4j.application.common;

import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralized logging utility that provides structured logging format across all classes.
 * Format: [timestamp][traceId][className][methodName][user=userName][session=sessionId] message
 */
public class LoggingUtil {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    public static final String TRACE_ID = "traceId";
    private static final String FALLBACK = "-";

    private LoggingUtil() {
        // Private constructor to prevent instantiation
    }

    /**
     * Log INFO level message with structured format
     */
    public static void logInfo(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage(className, method, message, args));
    }

    /**
     * Log DEBUG level message with structured format
     */
    public static void logDebug(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(className, method, message, args));
    }

    /**
     * Log WARN level message with structured format
     */
    public static void logWarn(Logger logger, String className, String method, String message, Object... args) {
        logger.warn(buildMessage(className, method, message, args));
    }

    /**
     * Log ERROR level message with structured format and exception
     */
    public static void logError(Logger logger, String className, String method, Throwable e, String message, Object... args) {
        String fullMessage = buildMessage(className, method, message, args);
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
    private static String buildMessage(String className, String method, String message, Object... args) {
        String formattedMsg = args.length > 0 ? String.format(message, args) : message;
        return '[' + getTimestamp() + ']' +
                '[' + getTraceId() + ']' +
                '[' + className + ']' +
                '[' + method + ']' +
                "[user=" + getUserName() + ']' +
                "[session=" + getSessionId() + "] " +
                formattedMsg;
    }

    /**
     * Get formatted current timestamp
     */
    private static String getTimestamp() {
        return ZonedDateTime.now().format(TIME_FORMATTER);
    }

    /**
     * Get traceId from MDC with fallback to "-"
     */
    private static String getTraceId() {
        String traceId = MDC.get(TRACE_ID);
        return traceId != null ? traceId : FALLBACK;
    }

    /**
     * Get userName from MDC with fallback to "-"
     */
    private static String getUserName() {
        String userName = MDC.get("userName");
        return userName != null ? userName : FALLBACK;
    }

    /**
     * Get sessionId from MDC with fallback to "-"
     */
    private static String getSessionId() {
        String sessionId = MDC.get("sessionId");
        return sessionId != null ? sessionId : FALLBACK;
    }
}
