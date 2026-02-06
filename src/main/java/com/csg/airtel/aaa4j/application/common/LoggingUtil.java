package com.csg.airtel.aaa4j.application.common;

import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralized logging utility that provides structured logging format across all classes.
 * Format: [timestamp][traceId][className][methodName][user=userName][session=sessionId] message
 *
 * Optimized for 3000+ TPS with zero unnecessary overhead:
 * - Overloaded methods (0-3 args) eliminate varargs Object[] allocation when level is disabled
 * - Level checking before message construction on ALL levels
 * - StringBuilder with pre-allocated capacity (avoids resize)
 * - Cached timestamp per-second to reduce DateTimeFormatter overhead
 * - Custom appendFormatted supports %s, %d, %.Nf, %% without String.format() overhead
 */
public class LoggingUtil {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    public static final String TRACE_ID = "traceId";
    private static final String MDC_USER = "userName";
    private static final String MDC_SESSION = "sessionId";
    private static final String FALLBACK = "-";

    // Cached timestamp per-second to avoid repeated formatting at high TPS
    private static final ThreadLocal<CachedTimestamp> CACHED_TS = new ThreadLocal<>();

    private LoggingUtil() {
    }

    // ============================================================
    // INFO - overloaded to avoid varargs Object[] allocation
    // ============================================================

    public static void logInfo(Logger logger, String className, String method, String message) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage(className, method, message));
    }

    public static void logInfo(Logger logger, String className, String method, String message, Object arg1) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage(className, method, message, arg1));
    }

    public static void logInfo(Logger logger, String className, String method, String message, Object arg1, Object arg2) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage(className, method, message, arg1, arg2));
    }

    public static void logInfo(Logger logger, String className, String method, String message, Object arg1, Object arg2, Object arg3) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage(className, method, message, arg1, arg2, arg3));
    }

    public static void logInfo(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage(className, method, message, args));
    }

    // ============================================================
    // DEBUG - overloaded to avoid varargs Object[] allocation
    // ============================================================

    public static void logDebug(Logger logger, String className, String method, String message) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(className, method, message));
    }

    public static void logDebug(Logger logger, String className, String method, String message, Object arg1) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(className, method, message, arg1));
    }

    public static void logDebug(Logger logger, String className, String method, String message, Object arg1, Object arg2) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(className, method, message, arg1, arg2));
    }

    public static void logDebug(Logger logger, String className, String method, String message, Object arg1, Object arg2, Object arg3) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(className, method, message, arg1, arg2, arg3));
    }

    public static void logDebug(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(className, method, message, args));
    }

    // ============================================================
    // WARN - overloaded to avoid varargs Object[] allocation
    // ============================================================

    public static void logWarn(Logger logger, String className, String method, String message) {
        if (!logger.isEnabled(Logger.Level.WARN)) return;
        logger.warn(buildMessage(className, method, message));
    }

    public static void logWarn(Logger logger, String className, String method, String message, Object arg1) {
        if (!logger.isEnabled(Logger.Level.WARN)) return;
        logger.warn(buildMessage(className, method, message, arg1));
    }

    public static void logWarn(Logger logger, String className, String method, String message, Object arg1, Object arg2) {
        if (!logger.isEnabled(Logger.Level.WARN)) return;
        logger.warn(buildMessage(className, method, message, arg1, arg2));
    }

    public static void logWarn(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isEnabled(Logger.Level.WARN)) return;
        logger.warn(buildMessage(className, method, message, args));
    }

    // ============================================================
    // ERROR - overloaded to avoid varargs Object[] allocation
    // ============================================================

    public static void logError(Logger logger, String className, String method, Throwable e, String message) {
        if (!logger.isEnabled(Logger.Level.ERROR)) return;
        String fullMessage = buildMessage(className, method, message);
        if (e != null) {
            logger.error(fullMessage, e);
        } else {
            logger.error(fullMessage);
        }
    }

    public static void logError(Logger logger, String className, String method, Throwable e, String message, Object arg1) {
        if (!logger.isEnabled(Logger.Level.ERROR)) return;
        String fullMessage = buildMessage(className, method, message, arg1);
        if (e != null) {
            logger.error(fullMessage, e);
        } else {
            logger.error(fullMessage);
        }
    }

    public static void logError(Logger logger, String className, String method, Throwable e, String message, Object arg1, Object arg2) {
        if (!logger.isEnabled(Logger.Level.ERROR)) return;
        String fullMessage = buildMessage(className, method, message, arg1, arg2);
        if (e != null) {
            logger.error(fullMessage, e);
        } else {
            logger.error(fullMessage);
        }
    }

    public static void logError(Logger logger, String className, String method, Throwable e, String message, Object... args) {
        if (!logger.isEnabled(Logger.Level.ERROR)) return;
        String fullMessage = buildMessage(className, method, message, args);
        if (e != null) {
            logger.error(fullMessage, e);
        } else {
            logger.error(fullMessage);
        }
    }

    // ============================================================
    // TRACE - overloaded to avoid varargs Object[] allocation
    // ============================================================

    public static void logTrace(Logger logger, String className, String method, String message) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(buildMessage(className, method, message));
    }

    public static void logTrace(Logger logger, String className, String method, String message, Object arg1) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(buildMessage(className, method, message, arg1));
    }

    public static void logTrace(Logger logger, String className, String method, String message, Object arg1, Object arg2) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(buildMessage(className, method, message, arg1, arg2));
    }

    public static void logTrace(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(buildMessage(className, method, message, args));
    }

    // ============================================================
    // Message building - zero-arg (no formatting needed)
    // ============================================================

    private static String buildMessage(String className, String method, String message) {
        String traceId = getMdcValue(TRACE_ID);
        String userName = getMdcValue(MDC_USER);
        String sessionId = getMdcValue(MDC_SESSION);

        int estimatedLength = 120 + (message != null ? message.length() : 0);
        StringBuilder sb = new StringBuilder(estimatedLength);

        appendPrefix(sb, traceId, className, method, userName, sessionId);
        sb.append(message);

        return sb.toString();
    }

    // ============================================================
    // Message building - with args (formatting needed)
    // ============================================================

    private static String buildMessage(String className, String method, String message, Object... args) {
        String traceId = getMdcValue(TRACE_ID);
        String userName = getMdcValue(MDC_USER);
        String sessionId = getMdcValue(MDC_SESSION);

        int estimatedLength = 120 + (message != null ? message.length() : 0);
        StringBuilder sb = new StringBuilder(estimatedLength);

        appendPrefix(sb, traceId, className, method, userName, sessionId);
        appendFormatted(sb, message, args);

        return sb.toString();
    }

    /**
     * Append the structured prefix: [timestamp][traceId][className][method][user=...][session=...]
     */
    private static void appendPrefix(StringBuilder sb, String traceId, String className,
                                     String method, String userName, String sessionId) {
        sb.append('[').append(getTimestamp()).append(']');
        sb.append('[').append(traceId).append(']');
        sb.append('[').append(className).append(']');
        sb.append('[').append(method).append(']');
        sb.append("[user=").append(userName).append(']');
        sb.append("[session=").append(sessionId).append("] ");
    }

    /**
     * Append formatted message to StringBuilder, replacing format specifiers.
     * Supports: %s, %d, %.Nf (e.g. %.2f, %.0f), %% (literal percent).
     * Falls back gracefully for unrecognized specifiers.
     */
    private static void appendFormatted(StringBuilder sb, String template, Object... args) {
        if (template == null) return;
        int argIndex = 0;
        int len = template.length();
        int i = 0;
        while (i < len) {
            char c = template.charAt(i);
            if (c == '%' && i + 1 < len) {
                char next = template.charAt(i + 1);

                // %% → literal %
                if (next == '%') {
                    sb.append('%');
                    i += 2;
                    continue;
                }

                // %s or %d → simple substitution
                if ((next == 's' || next == 'd') && argIndex < args.length) {
                    sb.append(args[argIndex++]);
                    i += 2;
                    continue;
                }

                // %.Nf → formatted float/double (e.g. %.2f, %.0f)
                if (next == '.' && argIndex < args.length) {
                    int fmtStart = i;
                    int j = i + 2; // skip "%."
                    // scan digits after the dot
                    while (j < len && Character.isDigit(template.charAt(j))) {
                        j++;
                    }
                    if (j < len && template.charAt(j) == 'f') {
                        String fmt = template.substring(fmtStart, j + 1);
                        sb.append(String.format(fmt, args[argIndex++]));
                        i = j + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
    }

    /**
     * Get formatted current timestamp with per-second caching.
     * At 3000 TPS, this avoids ~2999 redundant DateTimeFormatter calls per second.
     */
    private static String getTimestamp() {
        long currentSecond = System.currentTimeMillis() / 1000;
        CachedTimestamp cached = CACHED_TS.get();
        if (cached != null && cached.second == currentSecond) {
            return cached.formatted;
        }
        String formatted = ZonedDateTime.now().format(TIME_FORMATTER);
        CACHED_TS.set(new CachedTimestamp(currentSecond, formatted));
        return formatted;
    }

    private static String getMdcValue(String key) {
        String value = MDC.get(key);
        return value != null ? value : FALLBACK;
    }

    private static final class CachedTimestamp {
        final long second;
        final String formatted;

        CachedTimestamp(long second, String formatted) {
            this.second = second;
            this.formatted = formatted;
        }
    }
}
