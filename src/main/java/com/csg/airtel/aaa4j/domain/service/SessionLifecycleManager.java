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
     * @return Uni that completes when registration is done
     */
    public Uni<Void> onSessionCreated(String userId, Session session) {
        if (!config.enabled() || session == null || session.getSessionId() == null) {
            return Uni.createFrom().voidItem();
        }

        // Record session creation metric
        monitoringService.recordSessionCreated();

        long expiryTimeMillis = calculateExpiryTime(session.getSessionInitiatedTime());

        log.debugf("Registering new session in expiry index: userId=%s, sessionId=%s, expiryTime=%d",
                userId, session.getSessionId(), expiryTimeMillis);

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

        log.debugf("Updating session expiry on activity: userId=%s, sessionId=%s, newExpiryTime=%d",
                userId, sessionId, newExpiryTimeMillis);

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
     * Calculate expiry timestamp based on session start time and configured timeout.
     */
    private long calculateExpiryTime(LocalDateTime sessionInitiatedTime) {
        if (sessionInitiatedTime == null) {
            // Fallback to current time if no session time
            sessionInitiatedTime = LocalDateTime.now();
        }

        return sessionInitiatedTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .plus(Duration.ofMinutes(config.timeoutMinutes()))
                .toEpochMilli();
    }
}
