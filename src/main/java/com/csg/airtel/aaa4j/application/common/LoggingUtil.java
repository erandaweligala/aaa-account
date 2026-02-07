package com.csg.airtel.aaa4j.application.common;

import org.jboss.logging.Logger;
import org.slf4j.MDC;

/**
 * Zero-overhead structured logging utility optimized for 3000+ TPS.
 *
 * <p>Key optimizations:</p>
 * <ul>
 *   <li>ThreadLocal StringBuilder pool eliminates allocation per log call</li>
 *   <li>Overloaded methods for 0-3 args avoid varargs Object[] allocation</li>
 *   <li>Level guards before any work (MDC lookup, string building) ensure
 *       zero cost when a level is disabled</li>
 *   <li>Delegates to JBoss Logger overloaded infof/debugf methods which
 *       also avoid internal varargs allocation for up to 3 args</li>
 * </ul>
 *
 * <p>Format: [traceId][method][user=userName][session=sessionId] message</p>
 */
public class LoggingUtil {

    public static final String TRACE_ID = "traceId";
    private static final String FALLBACK = "-";

    // ThreadLocal StringBuilder pool - reused across calls, no GC pressure
    private static final ThreadLocal<StringBuilder> SB_POOL =
            ThreadLocal.withInitial(() -> new StringBuilder(256));

    private LoggingUtil() {
    }

    // ========================== INFO ==========================

    public static void logInfo(Logger logger, String method, String message) {
        if (!logger.isInfoEnabled()) return;
        logger.info(prefix(method, message));
    }

    public static void logInfo(Logger logger, String method, String message, Object arg1) {
        if (!logger.isInfoEnabled()) return;
        logger.infof(prefix(method, message), arg1);
    }

    public static void logInfo(Logger logger, String method, String message, Object arg1, Object arg2) {
        if (!logger.isInfoEnabled()) return;
        logger.infof(prefix(method, message), arg1, arg2);
    }

    public static void logInfo(Logger logger, String method, String message, Object arg1, Object arg2, Object arg3) {
        if (!logger.isInfoEnabled()) return;
        logger.infof(prefix(method, message), arg1, arg2, arg3);
    }

    public static void logInfo(Logger logger, String method, String message, Object... args) {
        if (!logger.isInfoEnabled()) return;
        logger.infof(prefix(method, message), args);
    }

    // ========================== DEBUG ==========================

    public static void logDebug(Logger logger, String method, String message) {
        if (!logger.isDebugEnabled()) return;
        logger.debug(prefix(method, message));
    }

    public static void logDebug(Logger logger, String method, String message, Object arg1) {
        if (!logger.isDebugEnabled()) return;
        logger.debugf(prefix(method, message), arg1);
    }

    public static void logDebug(Logger logger, String method, String message, Object arg1, Object arg2) {
        if (!logger.isDebugEnabled()) return;
        logger.debugf(prefix(method, message), arg1, arg2);
    }

    public static void logDebug(Logger logger, String method, String message, Object arg1, Object arg2, Object arg3) {
        if (!logger.isDebugEnabled()) return;
        logger.debugf(prefix(method, message), arg1, arg2, arg3);
    }

    public static void logDebug(Logger logger, String method, String message, Object... args) {
        if (!logger.isDebugEnabled()) return;
        logger.debugf(prefix(method, message), args);
    }

    // ========================== WARN ==========================

    public static void logWarn(Logger logger, String method, String message) {
        logger.warn(prefix(method, message));
    }

    public static void logWarn(Logger logger, String method, String message, Object arg1) {
        logger.warnf(prefix(method, message), arg1);
    }

    public static void logWarn(Logger logger, String method, String message, Object arg1, Object arg2) {
        logger.warnf(prefix(method, message), arg1, arg2);
    }

    public static void logWarn(Logger logger, String method, String message, Object arg1, Object arg2, Object arg3) {
        logger.warnf(prefix(method, message), arg1, arg2, arg3);
    }

    public static void logWarn(Logger logger, String method, String message, Object... args) {
        logger.warnf(prefix(method, message), args);
    }

    // ========================== ERROR ==========================

    public static void logError(Logger logger, String method, Throwable e, String message) {
        if (e != null) {
            logger.error(prefix(method, message), e);
        } else {
            logger.error(prefix(method, message));
        }
    }

    public static void logError(Logger logger, String method, Throwable e, String message, Object arg1) {
        String prefixed = prefix(method, message);
        if (e != null) {
            logger.errorf(e, prefixed, arg1);
        } else {
            logger.errorf(prefixed, arg1);
        }
    }

    public static void logError(Logger logger, String method, Throwable e, String message, Object arg1, Object arg2) {
        String prefixed = prefix(method, message);
        if (e != null) {
            logger.errorf(e, prefixed, arg1, arg2);
        } else {
            logger.errorf(prefixed, arg1, arg2);
        }
    }

    public static void logError(Logger logger, String method, Throwable e, String message, Object arg1, Object arg2, Object arg3) {
        String prefixed = prefix(method, message);
        if (e != null) {
            logger.errorf(e, prefixed, arg1, arg2, arg3);
        } else {
            logger.errorf(prefixed, arg1, arg2, arg3);
        }
    }

    public static void logError(Logger logger, String method, Throwable e, String message, Object... args) {
        String prefixed = prefix(method, message);
        if (e != null) {
            logger.errorf(e, prefixed, args);
        } else {
            logger.errorf(prefixed, args);
        }
    }

    // ========================== TRACE ==========================

    public static void logTrace(Logger logger, String method, String message) {
        if (!logger.isTraceEnabled()) return;
        logger.trace(prefix(method, message));
    }

    public static void logTrace(Logger logger, String method, String message, Object arg1) {
        if (!logger.isTraceEnabled()) return;
        logger.tracef(prefix(method, message), arg1);
    }

    public static void logTrace(Logger logger, String method, String message, Object arg1, Object arg2) {
        if (!logger.isTraceEnabled()) return;
        logger.tracef(prefix(method, message), arg1, arg2);
    }

    public static void logTrace(Logger logger, String method, String message, Object arg1, Object arg2, Object arg3) {
        if (!logger.isTraceEnabled()) return;
        logger.tracef(prefix(method, message), arg1, arg2, arg3);
    }

    public static void logTrace(Logger logger, String method, String message, Object... args) {
        if (!logger.isTraceEnabled()) return;
        logger.tracef(prefix(method, message), args);
    }

    // ========================== PREFIX BUILDER ==========================

    /**
     * Build structured prefix using ThreadLocal StringBuilder.
     * Single-pass construction, no intermediate String allocations.
     */
    private static String prefix(String method, String message) {
        StringBuilder sb = SB_POOL.get();
        sb.setLength(0);

        String traceId = MDC.get(TRACE_ID);
        String userName = MDC.get("userName");
        String sessionId = MDC.get("sessionId");

        sb.append('[');
        sb.append(traceId != null ? traceId : FALLBACK);
        sb.append("][");
        sb.append(method);
        sb.append("][user=");
        sb.append(userName != null ? userName : FALLBACK);
        sb.append("][session=");
        sb.append(sessionId != null ? sessionId : FALLBACK);
        sb.append("] ");
        sb.append(message);

        return sb.toString();
    }
}
