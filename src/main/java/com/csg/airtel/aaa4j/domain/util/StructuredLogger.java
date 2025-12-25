package com.csg.airtel.aaa4j.domain.util;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

//todo You’re right to be concerned. At ~2500 TPS, logging (not business logic) often becomes a top-3 bottleneck in AAA / RADIUS systems. Your StructuredLogger is well-written, but there are still real overhead sources that will show up at this TPS.
//todo 1. MDC cost (hidden but real)
//todo 2. StringBuilder + key=value formatting
//todo 4. INFO logs at request level
//
//At 2500 TPS:
//
//2500 INFO/sec
//
//1 pod × 7 pods = 17,500 logs/sec
//
//1 log ≈ 300–500 bytes
//
//~7–9 MB/sec logs
public class StructuredLogger {

    private final Logger logger;

    private StructuredLogger(Logger logger) {
        this.logger = logger;
    }

    public static StructuredLogger getLogger(Class<?> clazz) {
        return new StructuredLogger(Logger.getLogger(clazz));
    }

    public static StructuredLogger getLogger(String name) {
        return new StructuredLogger(Logger.getLogger(name));
    }

    /**
     * Set correlation context for distributed tracing
     */
    public static void setContext(String requestId, String userId, String sessionId) {
        if (requestId != null) MDC.put("requestId", requestId);
        if (userId != null) MDC.put("userId", userId);
        if (sessionId != null) MDC.put("sessionId", sessionId);
    }

    /**
     * Set operation context for tracking request types
     */
    public static void setOperation(String operation) {
        if (operation != null) MDC.put("operation", operation);
    }

    /**
     * Clear all MDC context (important for thread pool reuse)
     */
    public static void clearContext() {
        MDC.clear();
    }

    /**
     * Log structured info with additional fields
     */
    public void info(String message, Map<String, Object> fields) {
        if (logger.isInfoEnabled()) {
            String structuredMsg = formatStructured(message, fields);
            logger.info(structuredMsg);
        }
    }

    /**
     * Log structured info with lazy field evaluation (for performance)
     */
    public void info(String message, Supplier<Map<String, Object>> fieldsSupplier) {
        if (logger.isInfoEnabled()) {
            info(message, fieldsSupplier.get());
        }
    }

    /**
     * Log simple info message
     */
    public void info(String message) {
        logger.info(message);
    }

    /**
     * Log formatted info message
     */
    public void infof(String format, Object... params) {
        logger.infof(format, params);
    }

    /**
     * Log structured debug with additional fields
     */
    public void debug(String message, Map<String, Object> fields) {
        if (logger.isDebugEnabled()) {
            String structuredMsg = formatStructured(message, fields);
            logger.debug(structuredMsg);
        }
    }

    /**
     * Log structured debug with lazy field evaluation
     */
    public void debug(String message, Supplier<Map<String, Object>> fieldsSupplier) {
        if (logger.isDebugEnabled()) {
            debug(message, fieldsSupplier.get());
        }
    }

    /**
     * Log simple debug message
     */
    public void debug(String message) {
        logger.debug(message);
    }

    /**
     * Log formatted debug message
     */
    public void debugf(String format, Object... params) {
        logger.debugf(format, params);
    }

    /**
     * Log structured warning with additional fields
     */
    public void warn(String message, Map<String, Object> fields) {
        if (logger.isEnabled(Logger.Level.WARN)) {
            String structuredMsg = formatStructured(message, fields);
            logger.warn(structuredMsg);
        }
    }

    /**
     * Log simple warning message
     */
    public void warn(String message) {
        logger.warn(message);
    }

    /**
     * Log formatted warning message
     */
    public void warnf(String format, Object... params) {
        logger.warnf(format, params);
    }

    /**
     * Log structured error with additional fields
     */
    public void error(String message, Throwable throwable, Map<String, Object> fields) {
        String structuredMsg = formatStructured(message, fields);
        logger.error(structuredMsg, throwable);
    }

    /**
     * Log structured error with additional fields (no exception)
     */
    public void error(String message, Map<String, Object> fields) {
        String structuredMsg = formatStructured(message, fields);
        logger.error(structuredMsg);
    }

    /**
     * Log simple error message
     */
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }

    /**
     * Log simple error message
     */
    public void error(String message) {
        logger.error(message);
    }

    /**
     * Log formatted error message
     */
    public void errorf(String format, Object... params) {
        logger.errorf(format, params);
    }

    /**
     * Format message with structured fields (key=value format)
     * This format is easily parseable by log aggregators
     */
    private String formatStructured(String message, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return message;
        }

        StringBuilder sb = new StringBuilder(message);
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            sb.append(" ").append(entry.getKey()).append("=");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value);
            }
        }
        return sb.toString();
    }

    /**
     * Builder for structured log fields
     */
    public static class Fields {
        private final Map<String, Object> fields = new HashMap<>();

        public static Fields create() {
            return new Fields();
        }

        public Fields add(String key, Object value) {
            if (value != null) {
                fields.put(key, value);
            }
            return this;
        }

        public Fields addDuration(long durationMs) {
            fields.put("duration_ms", durationMs);
            return this;
        }

        public Fields addStatus(String status) {
            fields.put("status", status);
            return this;
        }

        public Fields addErrorCode(String errorCode) {
            fields.put("error_code", errorCode);
            return this;
        }

        public Fields addComponent(String component) {
            fields.put("component", component);
            return this;
        }

        public Map<String, Object> build() {
            return fields;
        }
    }

    /**
     * Check if debug is enabled (for guarding expensive operations)
     */
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    /**
     * Check if info is enabled
     */
    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }
}
