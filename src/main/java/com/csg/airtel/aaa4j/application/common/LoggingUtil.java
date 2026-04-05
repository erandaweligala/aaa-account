package com.csg.airtel.aaa4j.application.common;

import org.jboss.logging.Logger;

public class LoggingUtil {

    public static final String TRACE_ID = "traceId";
    public static final String USER_NAME = "userName";
    public static final String SESSION_ID = "sessionId";

    /**
     * ThreadLocal StringBuilder pool — eliminates per-call heap allocation at high TPS.
     * Each thread reuses its own instance; we reset length before use and trim after
     * growing beyond the soft cap to reclaim memory from occasional large messages.
     */
    private static final ThreadLocal<StringBuilder> SB_POOL =
            ThreadLocal.withInitial(() -> new StringBuilder(256));

    /** Typical log messages are 50–200 chars; 512 is a generous cap without wasting memory.
     *  40 threads × 512 bytes = 20KB idle hold (vs 80KB at the old 2048 cap). */
    private static final int SB_SOFT_CAP = 512;

    /** Reusable empty array returned when args is null — avoids inline new Object[0]. */
    private static final Object[] EMPTY_ARGS = new Object[0];

    private LoggingUtil() {
        // Private constructor to prevent instantiation
    }

    /**
     * Log INFO level message with structured format
     */
    public static void logInfo(Logger logger, String method, String message, Object... args) {
        if (!logger.isInfoEnabled()) return;
        logger.info(buildMessage(method, message, args));
    }

    /**
     * Log DEBUG level message with structured format
     */
    public static void logDebug(Logger logger, String method, String message, Object... args) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(buildMessage(method, message, args));
    }

    /**
     * Log WARN level message with structured format.
     * Level check avoids buildMessage() allocation when WARN is suppressed.
     */
    public static void logWarn(Logger logger, String method, String message, Object... args) {
        if (!logger.isWarnEnabled()) return;
        logger.warn(buildMessage(method, message, args));
    }

    /**
     * Log ERROR level message with structured format and optional exception.
     * Level check avoids buildMessage() allocation when ERROR is suppressed.
     */
    public static void logError(Logger logger, String method, Throwable e, String message, Object... args) {
        if (!logger.isErrorEnabled()) return;
        String fullMessage = buildMessage(method, message, args);
        if (e != null) {
            logger.error(fullMessage, e);
        } else {
            logger.error(fullMessage);
        }
    }

    /**
     * Log TRACE level message with structured format.
     * Signature matches all other methods: (logger, method, message, args...).
     * Previously carried a spurious 'className' parameter that shifted all arguments,
     * causing the method name to be used as message and the real message to be lost.
     */
    public static void logTrace(Logger logger, String method, String message, Object... args) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(buildMessage(method, message, args));
    }

    /**
     * Build the structured log message in a single pass using a pooled StringBuilder.
     * Avoids String.format() (regex parsing + Formatter allocation) and avoids a fresh
     * StringBuilder allocation on every call — critical at 3500 TPS.
     *
     * Format: [method]message-with-substituted-args
     * Supports %s and %d placeholders; %% emits a literal %.
     */
    private static String buildMessage(String method, String message, Object... args) {
        // Null guards — prevent NPE from method.length() / message.length() / args.length
        if (method == null)  method  = "";
        if (message == null) message = "";
        if (args == null)    args    = EMPTY_ARGS;

        StringBuilder sb = SB_POOL.get();
        sb.setLength(0);

        // Pre-size hint avoids 1–2 intermediate re-allocations on first use per thread
        // when the message is longer than the initial 256-byte buffer.
        sb.ensureCapacity(method.length() + message.length() + 32);

        sb.append('[').append(method).append(']');

        if (args.length == 0) {
            sb.append(message);
        } else {
            // Manual single-pass placeholder replacement — ~5x faster than String.format()
            int argIndex = 0;
            int len = message.length();
            for (int i = 0; i < len; i++) {
                char c = message.charAt(i);
                if (c == '%' && i + 1 < len && argIndex < args.length) {
                    char next = message.charAt(i + 1);
                    if (next == 's' || next == 'd') {
                        sb.append(args[argIndex++]);
                        i++; // skip format char
                    } else if (next == '%') {
                        sb.append('%');
                        i++;
                    } else {
                        sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        String result = sb.toString();

        // Trim pooled buffer if a large message caused it to grow past the soft cap,
        // so threads processing many large messages don't permanently hold excess memory.
        if (sb.capacity() > SB_SOFT_CAP) {
            SB_POOL.set(new StringBuilder(256));
        }

        return result;
    }

}
