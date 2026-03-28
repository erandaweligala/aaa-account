package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Listens for Redis keyspace expiry notifications to detect when a session's absolute TTL key expires.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>On session creation, {@link SessionLifecycleManager} stores a dedicated Redis string key
 *       {@code session:ttl:{userId}:{sessionId}} with an EXPIRE equal to the session's
 *       {@code absoluteTimeOut} (minutes converted to seconds).</li>
 *   <li>When Redis automatically deletes that key after the TTL elapses, it publishes an event on
 *       the channel {@code __keyevent@0__:expired}.</li>
 *   <li>This listener receives the expired key name, parses out userId and sessionId, and delegates
 *       to {@link IdleSessionTerminatorScheduler#terminateExpiredSession} for immediate cleanup.</li>
 * </ol>
 *
 * <h2>Redis prerequisite</h2>
 * Keyspace notifications must be enabled on the Redis server:
 * <pre>
 *   redis-cli CONFIG SET notify-keyspace-events Kx
 * </pre>
 * If not enabled, no events arrive and idle-timeout polling (scheduler) remains the sole
 * termination mechanism.
 */
@ApplicationScoped
public class SessionTtlListener {

    private static final Logger log = Logger.getLogger(SessionTtlListener.class);
    private static final String M_LISTENER = "sessionTtlListener";

    /**
     * Redis keyspace channel for expired key events on database 0.
     * Requires {@code notify-keyspace-events} to include {@code K} (keyspace) and {@code x} (expired).
     */
    private static final String KEYSPACE_EXPIRY_CHANNEL = "__keyevent@0__:expired";

    private final ReactiveRedisDataSource redisDataSource;
    private final IdleSessionTerminatorScheduler terminatorScheduler;
    private final IdleSessionConfig config;

    @Inject
    public SessionTtlListener(
            ReactiveRedisDataSource redisDataSource,
            IdleSessionTerminatorScheduler terminatorScheduler,
            IdleSessionConfig config) {
        this.redisDataSource = redisDataSource;
        this.terminatorScheduler = terminatorScheduler;
        this.config = config;
    }

    /**
     * Subscribe to Redis keyspace expiry notifications on application startup.
     * Skipped entirely when the idle-session feature is disabled.
     */
    void onStartup(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LoggingUtil.logInfo(log, M_LISTENER,
                    "Session TTL listener disabled (idle-session.enabled=false), skipping Redis keyspace subscription");
            return;
        }
        startListening();
    }

    private void startListening() {
        LoggingUtil.logInfo(log, M_LISTENER,
                "Subscribing to Redis keyspace expiry notifications on channel: %s", KEYSPACE_EXPIRY_CHANNEL);

        ReactivePubSubCommands<String> pubSub = redisDataSource.pubsub(String.class);
        pubSub.subscribe(KEYSPACE_EXPIRY_CHANNEL)
                .subscribe().with(
                        this::handleExpiredKey,
                        error -> LoggingUtil.logError(log, M_LISTENER, error,
                                "Redis TTL keyspace listener error — absolute TTL termination may be degraded; "
                                        + "idle-timeout scheduler remains active as fallback")
                );
    }

    /**
     * Handle a Redis keyspace expiry event.
     * Ignores keys that are not session TTL keys; delegates termination for those that are.
     *
     * @param expiredKey The full Redis key name that just expired
     */
    private void handleExpiredKey(String expiredKey) {
        if (expiredKey == null || !expiredKey.startsWith(SessionExpiryIndex.SESSION_TTL_KEY_PREFIX)) {
            return; // Not a session TTL key — ignore
        }

        SessionExpiryIndex.SessionExpiryEntry entry = SessionExpiryIndex.parseTtlMember(expiredKey);
        if (entry == null) {
            LoggingUtil.logWarn(log, M_LISTENER, "Could not parse session TTL key format: %s", expiredKey);
            return;
        }

        LoggingUtil.logInfo(log, M_LISTENER,
                "Session absolute TTL expired — triggering termination: userId=%s, sessionId=%s",
                entry.userId(), entry.sessionId());

        terminatorScheduler.terminateExpiredSession(entry.userId(), entry.sessionId())
                .subscribe().with(
                        v -> LoggingUtil.logInfo(log, M_LISTENER,
                                "Session terminated via TTL notification: userId=%s, sessionId=%s",
                                entry.userId(), entry.sessionId()),
                        error -> LoggingUtil.logError(log, M_LISTENER, error,
                                "Failed to terminate TTL-expired session: userId=%s, sessionId=%s",
                                entry.userId(), entry.sessionId())
                );
    }
}
