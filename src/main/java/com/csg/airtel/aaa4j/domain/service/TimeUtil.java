package com.csg.airtel.aaa4j.domain.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Centralized utility for consistent timezone handling across the application.
 * All timestamps should use this utility to ensure UTC consistency.
 *
 * Thread-safe singleton that provides:
 * - UTC timestamps for external APIs and events
 * - Explicit timezone handling for local operations
 * - Caching to reduce overhead of multiple calls within a request
 */
@ApplicationScoped
public class TimeUtil {

    private static final Logger log = Logger.getLogger(TimeUtil.class);

    /**
     * Default timezone for local operations.
     * Using UTC as the standard timezone for the entire application.
     * Change this if a different timezone is required (e.g., "Asia/Kolkata").
     */
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("UTC");

    private static final ThreadLocal<Instant> CACHED_INSTANT = new ThreadLocal<>();
    private static final ThreadLocal<LocalDateTime> CACHED_LOCAL_DATETIME = new ThreadLocal<>();
    private static final ThreadLocal<LocalDate> CACHED_LOCAL_DATE = new ThreadLocal<>();

    /**
     * Get current time as UTC Instant.
     * Use this for timestamps that need to be stored in databases or sent in APIs.
     * Results are cached per request thread for performance.
     *
     * @return Current time as UTC Instant
     */
    public Instant getCurrentTimeUTC() {
        Instant instant = CACHED_INSTANT.get();
        if (instant == null) {
            instant = Instant.now();
            CACHED_INSTANT.set(instant);
        }
        return instant;
    }

    /**
     * Get current time as LocalDateTime in the default timezone.
     * Use this for time window calculations and internal operations.
     * Results are cached per request thread for performance.
     *
     * @return Current LocalDateTime in DEFAULT_ZONE_ID timezone
     */
    public LocalDateTime getCurrentTimeLocal() {
        LocalDateTime dateTime = CACHED_LOCAL_DATETIME.get();
        if (dateTime == null) {
            dateTime = LocalDateTime.now(DEFAULT_ZONE_ID);
            CACHED_LOCAL_DATETIME.set(dateTime);
        }
        return dateTime;
    }

    /**
     * Get current date in the default timezone.
     * Use this for date comparisons and calculations.
     * Results are cached per request thread for performance.
     *
     * @return Current LocalDate in DEFAULT_ZONE_ID timezone
     */
    public LocalDate getCurrentDate() {
        LocalDate date = CACHED_LOCAL_DATE.get();
        if (date == null) {
            date = LocalDate.now(DEFAULT_ZONE_ID);
            CACHED_LOCAL_DATE.set(date);
        }
        return date;
    }

    /**
     * Get current time in a specific timezone.
     * Use when you need a specific timezone different from the default.
     *
     * @param zoneId The timezone to use
     * @return Current LocalDateTime in the specified timezone
     */
    public LocalDateTime getCurrentTimeInZone(ZoneId zoneId) {
        return LocalDateTime.now(zoneId);
    }

    /**
     * Get default timezone used by this application.
     *
     * @return The default ZoneId
     */
    public ZoneId getDefaultZoneId() {
        return DEFAULT_ZONE_ID;
    }

    /**
     * Clear all cached temporal values.
     * Call this at the end of request processing to prevent memory leaks
     * and ensure fresh values for the next request.
     */
    public void clearCache() {
        CACHED_INSTANT.remove();
        CACHED_LOCAL_DATETIME.remove();
        CACHED_LOCAL_DATE.remove();

        if (log.isTraceEnabled()) {
            log.tracef("Temporal cache cleared");
        }
    }
}
