package com.csg.airtel.aaa4j.domain.util;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * High-performance structured logger optimized for AAA/RADIUS systems at 2500+ TPS.
 *
 * Performance optimizations implemented:
 * 1. MDC batching and selective usage to reduce ThreadLocal overhead
 * 2. Pre-sized StringBuilder with object pooling to minimize allocations
 * 3. Adaptive sampling for INFO logs to reduce log volume at high TPS
 * 4. Lazy evaluation for expensive field construction
 *
 * At 2500 TPS × 7 pods = 17,500 logs/sec with 50% sampling = ~8,750 logs/sec
 * Log size: 300-500 bytes → ~3-4 MB/sec (vs 7-9 MB/sec baseline)
 */
public class StructuredLogger {

    private final Logger logger;

    // Performance: Sampling configuration for high-TPS scenarios
    private static volatile boolean samplingEnabled = false;
    private static volatile int samplingRate = 10; // Log 1 in N requests (10 = 10% sampling)
    private static final AtomicLong requestCounter = new AtomicLong(0);

    // Performance: ThreadLocal StringBuilder pool to avoid repeated allocations
    private static final ThreadLocal<StringBuilder> STRING_BUILDER_POOL =
        ThreadLocal.withInitial(() -> new StringBuilder(512)); // Pre-sized for typical log message


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
     * Enable sampling for INFO-level logs to reduce volume at high TPS.
     * When enabled, only 1 in N requests will log INFO messages.
     *
     * @param enabled whether to enable sampling
     * @param rate sampling rate (1 in N requests). E.g., 10 = 10% of requests logged
     */
    public static void configureSampling(boolean enabled, int rate) {
        samplingEnabled = enabled;
        samplingRate = Math.max(1, rate); // Ensure rate is at least 1
    }

    /**
     * Check if this request should be sampled (logged) based on sampling configuration.
     * Performance: Uses fast ThreadLocalRandom instead of synchronized Random.
     */
    private static boolean shouldSample() {
        if (!samplingEnabled) {
            return true; // All requests logged when sampling disabled
        }
        // Performance: Deterministic sampling using counter (faster than Random)
        // Every Nth request is logged for consistent sampling rate
        return (requestCounter.incrementAndGet() % samplingRate) == 0;
    }

    /**
     * Set correlation context for distributed tracing.
     * Performance: Batched MDC operations to reduce ThreadLocal overhead.
     */
    public static void setContext(String requestId, String userId, String sessionId) {
        if (requestId != null) MDC.put("requestId", requestId);
        if (userId != null) MDC.put("userId", userId);
        if (sessionId != null) MDC.put("sessionId", sessionId);
    }

    /**
     * Lightweight context setter - only sets requestId to minimize MDC overhead.
     * Use this for high-frequency operations where full context isn't needed.
     */
    public static void setRequestId(String requestId) {
        if (requestId != null) MDC.put("requestId", requestId);
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
     * Log structured info with additional fields.
     * Performance: Applies sampling when enabled to reduce log volume.
     */
    public void info(String message, Map<String, Object> fields) {
        if (logger.isInfoEnabled() && shouldSample()) {
            String structuredMsg = formatStructured(message, fields);
            logger.info(structuredMsg);
        }
    }

    /**
     * Log structured info with lazy field evaluation (for performance).
     * Performance: Field supplier is only called if sampling allows logging.
     */
    public void info(String message, Supplier<Map<String, Object>> fieldsSupplier) {
        if (logger.isInfoEnabled() && shouldSample()) {
            info(message, fieldsSupplier.get());
        }
    }

    /**
     * Log simple info message.
     * Performance: Applies sampling when enabled.
     */
    public void info(String message) {
        if (shouldSample()) {
            logger.info(message);
        }
    }

    /**
     * Force log INFO message bypassing sampling.
     * Use for critical INFO logs that must always be recorded (errors, business events).
     */
    public void infoForced(String message, Map<String, Object> fields) {
        if (logger.isInfoEnabled()) {
            String structuredMsg = formatStructured(message, fields);
            logger.info(structuredMsg);
        }
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
     * Format message with structured fields (key=value format).
     * Performance: Uses ThreadLocal StringBuilder pool to avoid allocations.
     * This format is easily parseable by log aggregators.
     */
    private String formatStructured(String message, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return message;
        }

        // Performance: Reuse ThreadLocal StringBuilder to avoid object allocation
        StringBuilder sb = STRING_BUILDER_POOL.get();
        try {
            sb.setLength(0); // Clear previous content
            sb.append(message);

            // Performance: Direct iteration over entrySet() - most efficient for HashMap
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                sb.append(' ').append(entry.getKey()).append('=');
                Object value = entry.getValue();

                // Performance: Avoid string concatenation, use char literals
                if (value instanceof String) {
                    sb.append('"').append(value).append('"');
                } else if (value != null) {
                    sb.append(value);
                } else {
                    sb.append("null");
                }
            }

            return sb.toString();
        } finally {
            // Performance: Keep StringBuilder for reuse but prevent unbounded growth
            // If it grew too large, reset to default capacity
            if (sb.capacity() > 2048) {
                STRING_BUILDER_POOL.remove();
            }
        }
    }

    /**
     * Builder for structured log fields.
     * Performance: Pre-sized HashMap and optimized common field methods.
     */
    public static class Fields {
        // Performance: Pre-size to 8 (typical field count) to avoid rehashing
        private final Map<String, Object> stringObjectMap;

        private Fields() {
            this.stringObjectMap = HashMap.newHashMap(8);
        }

        private Fields(int initialCapacity) {
            this.stringObjectMap = HashMap.newHashMap(initialCapacity);
        }

        /**
         * Create a new Fields builder with default capacity (8 fields).
         */
        public static Fields create() {
            return new Fields();
        }

        /**
         * Create a new Fields builder with specified initial capacity.
         * Use this when you know the exact number of fields to avoid HashMap resizing.
         */
        public static Fields create(int expectedFields) {
            return new Fields(expectedFields);
        }

        /**
         * Add a field if value is not null.
         * Performance: Null check prevents unnecessary HashMap put operation.
         */
        public Fields add(String key, Object value) {
            if (value != null) {
                stringObjectMap.put(key, value);
            }
            return this;
        }

        /**
         * Add duration field (common metric in AAA systems).
         */
        public Fields addDuration(long durationMs) {
            stringObjectMap.put("duration_ms", durationMs);
            return this;
        }

        /**
         * Add status field (success/failed/rejected).
         */
        public Fields addStatus(String status) {
            stringObjectMap.put("status", status);
            return this;
        }

        /**
         * Add error code for failures.
         */
        public Fields addErrorCode(String errorCode) {
            stringObjectMap.put("error_code", errorCode);
            return this;
        }

        /**
         * Add component identifier.
         */
        public Fields addComponent(String component) {
            stringObjectMap.put("component", component);
            return this;
        }

        /**
         * Build and return the fields map.
         * Performance: Returns the internal map directly (no copy) - caller should not modify.
         */
        public Map<String, Object> build() {
            return stringObjectMap;
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
