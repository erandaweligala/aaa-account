package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Manages Redis TTL keys for absolute session timeout.
 *
 * <p>Key format: {@code session:ttl:{userId}:{sessionId}}</p>
 * <p>Value: {@code {userId}:{sessionId}} (identity payload)</p>
 * <p>TTL: {@code absoluteTimeOut} in seconds from the session</p>
 *
 * <p>When the key expires, Redis fires a keyspace notification on
 * {@code __keyevent@0__:expired} which is picked up by
 * {@link com.csg.airtel.aaa4j.application.listener.SessionKeyspaceListener}
 * to terminate the session.</p>
 */
@ApplicationScoped
public class SessionTtlClient {

    private static final Logger log = Logger.getLogger(SessionTtlClient.class);
    private static final String M_TTL = "sessionTtl";

    public static final String TTL_KEY_PREFIX = "session:ttl:";
    private static final String SEPARATOR = ":";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;

    @Inject
    public SessionTtlClient(ReactiveRedisDataSource ds) {
        this.valueCommands = ds.value(String.class, String.class);
        this.keyCommands = ds.key();
    }

    /**
     * Sets a Redis key with the given TTL (in seconds) to track absolute session timeout.
     * When the key expires, the keyspace listener will terminate the session.
     *
     * @param userId     The user ID
     * @param sessionId  The session ID
     * @param ttlSeconds Absolute timeout in seconds
     */
    public Uni<Void> setAbsoluteTimeout(String userId, String sessionId, long ttlSeconds) {
        if (userId == null || sessionId == null || ttlSeconds <= 0) {
            return Uni.createFrom().voidItem();
        }

        String key = buildKey(userId, sessionId);
        String value = userId + SEPARATOR + sessionId;

        LoggingUtil.logDebug(log, M_TTL, "Setting absolute timeout TTL: key=%s ttl=%ds", key, ttlSeconds);

        return valueCommands.set(key, value, new SetArgs().ex(ttlSeconds))
                .onFailure().invoke(e ->
                        LoggingUtil.logWarn(log, M_TTL, "Failed to set absolute timeout TTL key=%s: %s", key, e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Removes the absolute timeout TTL key for a session.
     * Called when a session is terminated via STOP request (before TTL fires).
     *
     * @param userId    The user ID
     * @param sessionId The session ID
     */
    public Uni<Void> removeAbsoluteTimeoutKey(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return Uni.createFrom().voidItem();
        }

        String key = buildKey(userId, sessionId);
        LoggingUtil.logDebug(log, M_TTL, "Removing absolute timeout TTL key: %s", key);

        return keyCommands.del(key)
                .onFailure().invoke(e ->
                        LoggingUtil.logWarn(log, M_TTL, "Failed to remove absolute timeout TTL key=%s: %s", key, e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Returns true if the given Redis key is an absolute timeout TTL key.
     */
    public static boolean isAbsoluteTimeoutKey(String key) {
        return key != null && key.startsWith(TTL_KEY_PREFIX);
    }

    /**
     * Parses a TTL key into [userId, sessionId].
     *
     * @param key The expired Redis key
     * @return String array [userId, sessionId], or null if the key format is invalid
     */
    public static String[] parseKey(String key) {
        if (!isAbsoluteTimeoutKey(key)) {
            return null;
        }
        String payload = key.substring(TTL_KEY_PREFIX.length());
        int sepIdx = payload.indexOf(SEPARATOR);
        if (sepIdx <= 0 || sepIdx >= payload.length() - 1) {
            return null;
        }
        return new String[]{payload.substring(0, sepIdx), payload.substring(sepIdx + 1)};
    }

    static String buildKey(String userId, String sessionId) {
        return TTL_KEY_PREFIX + userId + SEPARATOR + sessionId;
    }
}
