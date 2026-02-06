package com.csg.airtel.aaa4j.application.common;

import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Centralized logging utility that provides structured logging format across all classes.
 * Format: [timestamp][traceId][className][methodName][user=userName][session=sessionId] message
 *
 * Optimized for 3000+ TPS:
 * - Level checking before message construction
 * - ThreadLocal StringBuilder reuse (avoids allocation + GC per message)
 * - No-varargs overloads to avoid empty Object[] allocation on every call
 * - Cached timestamp per-second to reduce DateTimeFormatter overhead
 * - Instant-based timestamp on cache miss (lighter than ZonedDateTime.now())
 * - No String.format() usage (replaced with direct StringBuilder append)
 */
public class LoggingUtil {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
    public static final String TRACE_ID = "traceId";
    private static final String FALLBACK = "-";

    // Cached timestamp per-second to avoid repeated formatting at high TPS
    private static final ThreadLocal<CachedTimestamp> CACHED_TS = new ThreadLocal<>();

    // Reusable StringBuilder per thread to avoid allocation on every log call
    private static final ThreadLocal<StringBuilder> SB_HOLDER = ThreadLocal.withInitial(() -> new StringBuilder(256));

    private LoggingUtil() {
        // Private constructor to prevent instantiation
    }

    // ── No-varargs overloads (avoids empty Object[] allocation) ──────────

    /**
     * Log INFO level message with structured format (no args variant).
     */
    public static void logInfo(Logger logger, String className, String method, String message) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage(className, method, message));
    }

    /**
     * Log DEBUG level message with structured format (no args variant).
     */
    public static void logDebug(Logger logger, String className, String method, String message) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(className, method, message));
    }

    /**
     * Log WARN level message with structured format (no args variant).
     */
    public static void logWarn(Logger logger, String className, String method, String message) {
        if (!logger.isWarnEnabled()) return;
        logger.warn(buildMessage(className, method, message));
    }

    /**
     * Log ERROR level message with structured format and exception (no args variant).
     */
    public static void logError(Logger logger, String className, String method, Throwable e, String message) {
        String fullMessage = buildMessage(className, method, message);
        if (e != null) {
            logger.error(fullMessage, e);
        } else {
            logger.error(fullMessage);
        }
    }

    /**
     * Log TRACE level message with structured format (no args variant).
     */
    public static void logTrace(Logger logger, String className, String method, String message) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(buildMessage(className, method, message));
    }

    // ── Varargs overloads (for calls that need placeholder substitution) ─

    /**
     * Log INFO level message with structured format.
     */
    public static void logInfo(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessageWithArgs(className, method, message, args));
    }

    /**
     * Log DEBUG level message with structured format.
     */
    public static void logDebug(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessageWithArgs(className, method, message, args));
    }

    /**
     * Log WARN level message with structured format.
     */
    public static void logWarn(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isWarnEnabled()) return;
        logger.warn(buildMessageWithArgs(className, method, message, args));
    }

    /**
     * Log ERROR level message with structured format and exception.
     */
    public static void logError(Logger logger, String className, String method, Throwable e, String message, Object... args) {
        String fullMessage = buildMessageWithArgs(className, method, message, args);
        if (e != null) {
            logger.error(fullMessage, e);
        } else {
            logger.error(fullMessage);
        }
    }

    /**
     * Log TRACE level message with structured format.
     */
    public static void logTrace(Logger logger, String className, String method, String message, Object... args) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(buildMessageWithArgs(className, method, message, args));
    }

    // ── Message building ─────────────────────────────────────────────────

    /**
     * Build structured log message without placeholder substitution.
     * Uses ThreadLocal StringBuilder to avoid allocation per call.
     */
    private static String buildMessage(String className, String method, String message) {
        StringBuilder sb = acquireStringBuilder();
        appendPrefix(sb, className, method);
        sb.append(message);
        return sb.toString();
    }

    /**
     * Build structured log message with placeholder substitution.
     * Uses ThreadLocal StringBuilder to avoid allocation per call.
     */
    private static String buildMessageWithArgs(String className, String method, String message, Object[] args) {
        StringBuilder sb = acquireStringBuilder();
        appendPrefix(sb, className, method);
        if (args.length > 0) {
            appendFormatted(sb, message, args);
        } else {
            sb.append(message);
        }
        return sb.toString();
    }

    /**
     * Append the common structured prefix to the StringBuilder.
     */
    private static void appendPrefix(StringBuilder sb, String className, String method) {
        String traceId = getMdcValue(TRACE_ID);
        String userName = getMdcValue("userName");
        String sessionId = getMdcValue("sessionId");

        sb.append('[').append(getTimestamp()).append(']');
        sb.append('[').append(traceId).append(']');
        sb.append('[').append(className).append(']');
        sb.append('[').append(method).append(']');
        sb.append("[user=").append(userName).append(']');
        sb.append("[session=").append(sessionId).append("] ");
    }

    /**
     * Acquire and reset the ThreadLocal StringBuilder for reuse.
     */
    private static StringBuilder acquireStringBuilder() {
        StringBuilder sb = SB_HOLDER.get();
        sb.setLength(0);
        return sb;
    }

    /**
     * Append formatted message to StringBuilder, replacing %s/%d placeholders.
     * Faster than String.format() for simple placeholder substitution.
     */
    private static void appendFormatted(StringBuilder sb, String template, Object[] args) {
        if (template == null) return;
        int argIndex = 0;
        int len = template.length();
        int i = 0;
        while (i < len) {
            char c = template.charAt(i);
            if (c == '%' && i + 1 < len && argIndex < args.length) {
                char next = template.charAt(i + 1);
                if (next == 's' || next == 'd') {
                    sb.append(args[argIndex++]);
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
    }

    /**
     * Get formatted current timestamp with per-second caching.
     * At 3000 TPS, this avoids ~2999 redundant DateTimeFormatter calls per second.
     * Uses Instant.now() instead of ZonedDateTime.now() on cache miss for lower overhead.
     */
    private static String getTimestamp() {
        long currentSecond = System.currentTimeMillis() / 1000;
        CachedTimestamp cached = CACHED_TS.get();
        if (cached != null && cached.second == currentSecond) {
            return cached.formatted;
        }
        String formatted = Instant.now().atZone(SYSTEM_ZONE).format(TIME_FORMATTER);
        CACHED_TS.set(new CachedTimestamp(currentSecond, formatted));
        return formatted;
    }

    /**
     * Get MDC value with fallback to "-"
     */
    private static String getMdcValue(String key) {
        String value = MDC.get(key);
        return value != null ? value : FALLBACK;
    }

    /**
     * Cached timestamp holder to avoid repeated DateTimeFormatter calls within same second.
     */
    private static final class CachedTimestamp {
        final long second;
        final String formatted;

        CachedTimestamp(long second, String formatted) {
            this.second = second;
            this.formatted = formatted;
        }
    }
}
