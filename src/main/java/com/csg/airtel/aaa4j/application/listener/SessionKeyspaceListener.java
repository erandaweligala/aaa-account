package com.csg.airtel.aaa4j.application.listener;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.service.AbsoluteSessionTerminatorService;
import com.csg.airtel.aaa4j.external.clients.SessionTtlClient;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Listens for Redis keyspace expiry notifications to implement event-driven
 * absolute session timeout.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>On startup, enables Redis keyspace notifications ({@code Ex}) via CONFIG SET.</li>
 *   <li>Subscribes to {@code __keyevent@0__:expired}.</li>
 *   <li>Filters events whose key matches the {@code session:ttl:} prefix.</li>
 *   <li>Parses {@code userId} and {@code sessionId} from the key.</li>
 *   <li>Delegates to {@link AbsoluteSessionTerminatorService} to clean up the session.</li>
 * </ol>
 *
 * <h2>Redis prerequisite</h2>
 * <p>Keyspace notifications are enabled automatically at startup via
 * {@code CONFIG SET notify-keyspace-events Ex}. No manual Redis configuration is required.</p>
 */
@ApplicationScoped
public class SessionKeyspaceListener {

    private static final Logger log = Logger.getLogger(SessionKeyspaceListener.class);
    private static final String M_LISTEN = "keyspaceListener";

    /**
     * Redis keyevent channel for DB 0 expired-key events.
     * Format: __keyevent@{db-index}__:{event}
     */
    private static final String EXPIRED_CHANNEL = "__keyevent@0__:expired";

    private final ReactiveRedisDataSource reactiveRedisDataSource;
    private final AbsoluteSessionTerminatorService absoluteSessionTerminatorService;

    @Inject
    public SessionKeyspaceListener(ReactiveRedisDataSource reactiveRedisDataSource,
                                   AbsoluteSessionTerminatorService absoluteSessionTerminatorService) {
        this.reactiveRedisDataSource = reactiveRedisDataSource;
        this.absoluteSessionTerminatorService = absoluteSessionTerminatorService;
    }

    /**
     * Enables keyspace notifications in Redis and subscribes to expiry events.
     * Runs once at application startup.
     */
    void onStart(@Observes StartupEvent ev) {
        enableKeyspaceNotifications();
        subscribeToExpiredEvents();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void enableKeyspaceNotifications() {
        // "E" = Keyevent events, "x" = Expired events
        reactiveRedisDataSource.execute(String.class, "CONFIG", "SET", "notify-keyspace-events", "Ex")
                .subscribe().with(
                        result -> LoggingUtil.logInfo(log, M_LISTEN,
                                "Redis keyspace notifications enabled (notify-keyspace-events=Ex): %s", result),
                        e -> LoggingUtil.logWarn(log, M_LISTEN,
                                "Could not set notify-keyspace-events (may require redis-server config): %s", e.getMessage())
                );
    }

    private void subscribeToExpiredEvents() {
        ReactivePubSubCommands<String> pubSub = reactiveRedisDataSource.pubsub(String.class);

        pubSub.subscribe(EXPIRED_CHANNEL, this::handleExpiredKey)
                .subscribe().with(
                        v -> LoggingUtil.logInfo(log, M_LISTEN,
                                "Subscribed to Redis keyspace channel: %s", EXPIRED_CHANNEL),
                        e -> LoggingUtil.logError(log, M_LISTEN, e,
                                "Failed to subscribe to Redis keyspace channel: %s", EXPIRED_CHANNEL)
                );
    }

    private void handleExpiredKey(String expiredKey) {
        if (!SessionTtlClient.isAbsoluteTimeoutKey(expiredKey)) {
            return; // Not our key – ignore
        }

        String[] parts = SessionTtlClient.parseKey(expiredKey);
        if (parts == null) {
            LoggingUtil.logWarn(log, M_LISTEN, "Could not parse expired TTL key: %s", expiredKey);
            return;
        }

        String userId    = parts[0];
        String sessionId = parts[1];

        LoggingUtil.logInfo(log, M_LISTEN,
                "Absolute timeout TTL expired: userId=%s sessionId=%s", userId, sessionId);

        absoluteSessionTerminatorService.terminateSession(userId, sessionId)
                .subscribe().with(
                        v -> LoggingUtil.logInfo(log, M_LISTEN,
                                "Absolute timeout session terminated: userId=%s sessionId=%s", userId, sessionId),
                        e -> LoggingUtil.logError(log, M_LISTEN, e,
                                "Failed to terminate absolute timeout session: userId=%s sessionId=%s", userId, sessionId)
                );
    }
}