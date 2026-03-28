package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
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
 *
 * <h2>Expiry Time Calculation</h2>
 * <p>Sessions are indexed with an expiry time = sessionInitiatedTime + timeoutMinutes.
 * On activity, the expiry time is updated to = currentTime + timeoutMinutes.</p>
 */
@ApplicationScoped
public class SessionLifecycleManager {
    private static final Logger log = Logger.getLogger(SessionLifecycleManager.class);
    private static final String M_CREATE = "onSessionCreated";
    private static final String M_ACTIVITY = "onSessionActivity";
    private static final String M_TERMINATE = "onSessionTerminated";

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
     * @return Uni that completes when registration is done
     */
    public Uni<Void> onSessionCreated(String userId, Session session) {
        if (!config.enabled() || session == null || session.getSessionId() == null) {
            return Uni.createFrom().voidItem();
        }

        // Record session creation metric
        monitoringService.recordSessionCreated();

        long expiryTimeMillis = calculateExpiryTime(session.getSessionInitiatedTime());
        long ttlSeconds = calculateAbsoluteTtlSeconds(session);

        LoggingUtil.logDebug(log, M_CREATE, "Registering new session: userId=%s, sessionId=%s, idleExpiryTime=%d, absoluteTtlSecs=%d",
                userId, session.getSessionId(), expiryTimeMillis, ttlSeconds);

        // Register in idle-timeout sorted set index
        Uni<Void> idleIndexUni = sessionExpiryIndex.registerSession(userId, session.getSessionId(), expiryTimeMillis)
                .onFailure().invoke(e ->
                        LoggingUtil.logWarn(log, M_CREATE, "Failed to register session in expiry index: %s", e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();

        // Set dedicated TTL key for absolute session timeout — triggers keyspace expiry notification
        Uni<Void> ttlKeyUni = sessionExpiryIndex.setSessionTtlKey(userId, session.getSessionId(), ttlSeconds)
                .onFailure().invoke(e ->
                        LoggingUtil.logWarn(log, M_CREATE, "Failed to set session TTL key: %s", e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();

        return Uni.combine().all().unis(idleIndexUni, ttlKeyUni).discardItems();
    }

    /**
     * Called when session activity is detected (e.g., INTERIM update).
     * Updates the session's expiry time in the index.
     *
     * @param userId The user ID
     * @param sessionId The session ID
     * @return Uni that completes when update is done
     */
    public Uni<Void> onSessionActivity(String userId, String sessionId) {
        if (!config.enabled() || userId == null || sessionId == null) {
            return Uni.createFrom().voidItem();
        }

        // Reset expiry time based on current time + timeout
        long newExpiryTimeMillis = Instant.now()
                .plus(Duration.ofMinutes(config.timeoutMinutes()))
                .toEpochMilli();

        LoggingUtil.logDebug(log, M_ACTIVITY, "Updating session expiry on activity: userId=%s, sessionId=%s, newExpiryTime=%d",
                userId, sessionId, newExpiryTimeMillis);

        return sessionExpiryIndex.updateSessionExpiry(userId, sessionId, newExpiryTimeMillis)
                .onFailure().invoke(e ->
                        LoggingUtil.logWarn(log, M_ACTIVITY, "Failed to update session expiry: %s", e.getMessage()))
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

        LoggingUtil.logDebug(log, M_TERMINATE, "Removing terminated session from expiry index and TTL key: userId=%s, sessionId=%s",
                userId, sessionId);

        // Remove from idle-timeout sorted set index
        Uni<Void> idleIndexUni = sessionExpiryIndex.removeSession(userId, sessionId)
                .onFailure().invoke(e ->
                        LoggingUtil.logWarn(log, M_TERMINATE, "Failed to remove session from expiry index: %s", e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();

        // Delete dedicated TTL key so it doesn't fire after normal termination
        Uni<Void> ttlKeyUni = sessionExpiryIndex.deleteSessionTtlKey(userId, sessionId)
                .onFailure().invoke(e ->
                        LoggingUtil.logWarn(log, M_TERMINATE, "Failed to delete session TTL key: %s", e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();

        return Uni.combine().all().unis(idleIndexUni, ttlKeyUni).discardItems();
    }

    /**
     * Calculate idle-timeout expiry timestamp based on session start time and configured idle timeout.
     * This value slides forward on each INTERIM update via onSessionActivity.
     */
    private long calculateExpiryTime(LocalDateTime sessionInitiatedTime) {
        if (sessionInitiatedTime == null) {
            sessionInitiatedTime = LocalDateTime.now();
        }

        return sessionInitiatedTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .plus(Duration.ofMinutes(config.timeoutMinutes()))
                .toEpochMilli();
    }

    /**
     * Calculate the absolute TTL in seconds for a session's dedicated Redis TTL key.
     * Uses the session's absoluteTimeOut field (minutes) when available; falls back to
     * the configured idle timeout.
     */
    private long calculateAbsoluteTtlSeconds(Session session) {
        if (session.getAbsoluteTimeOut() != null) {
            try {
                long timeoutMinutes = Long.parseLong(session.getAbsoluteTimeOut().trim());
                return timeoutMinutes * 60L;
            } catch (NumberFormatException e) {
                LoggingUtil.logWarn(log, M_CREATE,
                        "Invalid absoluteTimeOut format '%s' for sessionId=%s, using configured default",
                        session.getAbsoluteTimeOut(), session.getSessionId());
            }
        }
        return (long) config.timeoutMinutes() * 60L;
    }
}
