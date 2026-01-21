package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Manages session lifecycle events and maintains the session expiry index.
 *
 * <p>This service should be called whenever sessions are created, updated, or terminated
 * to keep the expiry index in sync for efficient idle session detection.</p>
 *
 * <h2>Integration Points</h2>
 * <ul>
 *   <li>{@code StartHandler.processAccountingStart()} - Call {@link #onSessionCreated} when creating new sessions</li>
 *   <li>{@code InterimHandler.handleInterim()} - Call {@link #onSessionActivity} to update expiry time</li>
 *   <li>{@code StopHandler.stopProcessing()} - Call {@link #onSessionTerminated} when terminating sessions</li>
 * </ul>
 *
 * <h2>Expiry Time Calculation</h2>
 * <p>Sessions are indexed with an expiry time = sessionInitiatedTime + timeoutMinutes.
 * On activity, the expiry time is updated to = currentTime + timeoutMinutes.</p>
 */
@ApplicationScoped
public class SessionLifecycleManager {
    private static final Logger log = Logger.getLogger(SessionLifecycleManager.class);

    private final SessionExpiryIndex sessionExpiryIndex;
    private final IdleSessionConfig config;
    private final MonitoringService monitoringService;

    @Inject
    public SessionLifecycleManager(SessionExpiryIndex sessionExpiryIndex,
                                   IdleSessionConfig config,
                                   MonitoringService monitoringService) {
        this.sessionExpiryIndex = sessionExpiryIndex;
        this.config = config;
        this.monitoringService = monitoringService;
    }

    /**
     * Called when a new session is created.
     * Registers the session in the expiry index with calculated expiry time.
     *
     * @param userId The user ID
     * @param session The newly created session
     * @param sessionTimeout The session timeout string from UserSessionData (e.g., "24 hours", "1 day")
     * @return Uni that completes when registration is done
     */
    public Uni<Void> onSessionCreated(String userId, Session session, String sessionTimeout) {
        if (!config.enabled() || session == null || session.getSessionId() == null) {
            return Uni.createFrom().voidItem();
        }

        // Record session creation metric
        monitoringService.recordSessionCreated();

        Duration timeout = parseSessionTimeout(sessionTimeout);
        long expiryTimeMillis = calculateExpiryTime(session.getSessionInitiatedTime(), timeout);

        log.debugf("Registering new session in expiry index: userId=%s, sessionId=%s, expiryTime=%d, timeout=%s",
                userId, session.getSessionId(), expiryTimeMillis, timeout);

        return sessionExpiryIndex.registerSession(userId, session.getSessionId(), expiryTimeMillis)
                .onFailure().invoke(e ->
                        log.warnf("Failed to register session in expiry index: %s", e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Called when session activity is detected (e.g., INTERIM update).
     * Updates the session's expiry time in the index.
     *
     * @param userId The user ID
     * @param sessionId The session ID
     * @param sessionTimeout The session timeout string from UserSessionData (e.g., "24 hours", "1 day")
     * @return Uni that completes when update is done
     */
    public Uni<Void> onSessionActivity(String userId, String sessionId, String sessionTimeout) {
        if (!config.enabled() || userId == null || sessionId == null) {
            return Uni.createFrom().voidItem();
        }

        // Reset expiry time based on current time + timeout
        Duration timeout = parseSessionTimeout(sessionTimeout);
        long newExpiryTimeMillis = Instant.now()
                .plus(timeout)
                .toEpochMilli();

        log.debugf("Updating session expiry on activity: userId=%s, sessionId=%s, newExpiryTime=%d, timeout=%s",
                userId, sessionId, newExpiryTimeMillis, timeout);

        return sessionExpiryIndex.updateSessionExpiry(userId, sessionId, newExpiryTimeMillis)
                .onFailure().invoke(e ->
                        log.warnf("Failed to update session expiry: %s", e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Called when a session is terminated.
     * Removes the session from the expiry index.
     *
     * @param userId The user ID
     * @param sessionId The session ID
     * @return Uni that completes when removal is done
     */
    public Uni<Void> onSessionTerminated(String userId, String sessionId) {
        if (!config.enabled() || userId == null || sessionId == null) {
            return Uni.createFrom().voidItem();
        }

        // Record session termination metric
        monitoringService.recordSessionTerminated();

        log.debugf("Removing terminated session from expiry index: userId=%s, sessionId=%s",
                userId, sessionId);

        return sessionExpiryIndex.removeSession(userId, sessionId)
                .onFailure().invoke(e ->
                        log.warnf("Failed to remove session from expiry index: %s", e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Calculate expiry timestamp based on session start time and timeout duration.
     *
     * @param sessionInitiatedTime The time when the session was initiated
     * @param timeout The timeout duration
     * @return The expiry timestamp in milliseconds
     */
    private long calculateExpiryTime(LocalDateTime sessionInitiatedTime, Duration timeout) {
        if (sessionInitiatedTime == null) {
            // Fallback to current time if no session time
            sessionInitiatedTime = LocalDateTime.now();
        }

        return sessionInitiatedTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .plus(timeout)
                .toEpochMilli();
    }

    /**
     * Parse session timeout string to Duration.
     * Supports formats like:
     * - "24 hours", "1 hour"
     * - "1 day", "2 days"
     * - "60 minutes", "30 min"
     * - "PT24H" (ISO-8601 duration format)
     * - null or empty string (falls back to config default)
     *
     * @param sessionTimeoutStr The session timeout string
     * @return Duration object
     */
    private Duration parseSessionTimeout(String sessionTimeoutStr) {
        if (sessionTimeoutStr == null || sessionTimeoutStr.trim().isEmpty()) {
            log.debugf("Session timeout is null or empty, using default: %d minutes", config.timeoutMinutes());
            return Duration.ofMinutes(config.timeoutMinutes());
        }

        String timeoutStr = sessionTimeoutStr.trim().toLowerCase();

        try {
            // Try parsing as ISO-8601 duration first (e.g., "PT24H", "P1D")
            if (timeoutStr.startsWith("pt") || timeoutStr.startsWith("p")) {
                return Duration.parse(sessionTimeoutStr);
            }

            // Parse human-readable formats
            String[] parts = timeoutStr.split("\\s+");
            if (parts.length < 2) {
                log.warnf("Invalid session timeout format: '%s', using default: %d minutes",
                        sessionTimeoutStr, config.timeoutMinutes());
                return Duration.ofMinutes(config.timeoutMinutes());
            }

            long value = Long.parseLong(parts[0]);
            String unit = parts[1];

            // Handle different time units
            return switch (unit) {
                case "hour", "hours", "h" -> Duration.ofHours(value);
                case "minute", "minutes", "min", "mins", "m" -> Duration.ofMinutes(value);
                case "day", "days", "d" -> Duration.ofDays(value);
                case "second", "seconds", "sec", "secs", "s" -> Duration.ofSeconds(value);
                default -> {
                    log.warnf("Unknown time unit '%s' in timeout: '%s', using default: %d minutes",
                            unit, sessionTimeoutStr, config.timeoutMinutes());
                    yield Duration.ofMinutes(config.timeoutMinutes());
                }
            };
        } catch (Exception e) {
            log.warnf(e, "Failed to parse session timeout '%s', using default: %d minutes",
                    sessionTimeoutStr, config.timeoutMinutes());
            return Duration.ofMinutes(config.timeoutMinutes());
        }
    }
}
