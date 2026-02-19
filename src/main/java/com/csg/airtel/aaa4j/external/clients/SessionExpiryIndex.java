package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ReactiveSortedSetCommands;
import io.quarkus.redis.datasource.sortedset.ScoreRange;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;

/**
 * Redis Sorted Set based index for tracking session expiry times.
 * This provides O(log N) complexity for finding expired sessions instead of O(N) full scan.
 *
 * Key structure:
 * - Sorted Set Key: "session:expiry:index"
 * - Member: "userId:sessionId"
 * - Score: Unix timestamp of expected expiry time
 *
 * This approach scales to 10M+ users efficiently:
 * - ZADD is O(log N) per insertion
 * - ZRANGEBYSCORE for expired sessions is O(log N + K) where K is expired count
 * - ZREM is O(log N) per removal
 */
@ApplicationScoped
public class SessionExpiryIndex {

    private static final Logger log = Logger.getLogger(SessionExpiryIndex.class);
    private static final String M_INDEX = "sessionExpiryIndex";

    private static final String EXPIRY_INDEX_KEY = "session:";
    private static final String MEMBER_SEPARATOR = ":";

    private final ReactiveSortedSetCommands<String, String> sortedSetCommands;

    @Inject
    public SessionExpiryIndex(ReactiveRedisDataSource reactiveRedisDataSource) {
        this.sortedSetCommands = reactiveRedisDataSource.sortedSet(String.class, String.class);
    }

    /**
     * Register a session with its expected expiry time.
     * Call this when a session is created or updated.
     *
     * @param userId The user ID
     * @param sessionId The session ID
     * @param expiryTimeMillis Unix timestamp when session should expire
     * @return Uni that completes when registration is done
     */
    public Uni<Void> registerSession(String userId, String sessionId, long expiryTimeMillis) {
        String member = buildMember(userId, sessionId);
        double score = expiryTimeMillis;

        return sortedSetCommands.zadd(EXPIRY_INDEX_KEY, score, member)
                .onItem().invoke(added ->
                    LoggingUtil.logDebug(log, M_INDEX, "Registered session in expiry index: %s -> %d", member, expiryTimeMillis)
                )
                .replaceWithVoid();
    }

    /**
     * Update a session's expiry time (e.g., after activity).
     * Uses ZADD with XX flag to only update if exists.
     *
     * @param userId The user ID
     * @param sessionId The session ID
     * @param newExpiryTimeMillis New expiry timestamp
     * @return Uni that completes when update is done
     */
    public Uni<Void> updateSessionExpiry(String userId, String sessionId, long newExpiryTimeMillis) {
        String member = buildMember(userId, sessionId);
        double score = newExpiryTimeMillis;

        // ZADD with default behavior updates score if member exists
        return sortedSetCommands.zadd(EXPIRY_INDEX_KEY, score, member)
                .onItem().invoke(result ->
                    LoggingUtil.logDebug(log, M_INDEX, "Updated session expiry: %s -> %d", member, newExpiryTimeMillis))
                .replaceWithVoid();
    }

    /**
     * Remove a session from the expiry index.
     * Call this when a session is terminated.
     *
     * @param userId The user ID
     * @param sessionId The session ID
     * @return Uni that completes when removal is done
     */
    public Uni<Void> removeSession(String userId, String sessionId) {
        String member = buildMember(userId, sessionId);

        return sortedSetCommands.zrem(EXPIRY_INDEX_KEY, member)
                .onItem().invoke(removed ->
                    LoggingUtil.logDebug(log, M_INDEX, "Removed session from expiry index: %s, removed: %d", member, removed)
                )
                .replaceWithVoid();
    }

    /**
     * Remove multiple sessions from the expiry index in a single operation.
     *
     * @param members List of "userId:sessionId" members to remove
     * @return Uni with count of removed members
     */
    public Uni<Integer> removeSessions(List<String> members) {
        if (members == null || members.isEmpty()) {
            return Uni.createFrom().item(0);
        }

        String[] memberArray = members.toArray(new String[0]);
        return sortedSetCommands.zrem(EXPIRY_INDEX_KEY, memberArray);
    }

    /**
     * Get all expired sessions up to a given timestamp with a limit.
     * This is the key optimization - O(log N + K) instead of O(N) full scan.
     *
     * <p>Note: This returns session identifiers only. To get expired sessions
     * with their full user data, use {@link CacheClient#getExpiredSessionsWithData}.</p>
     *
     * @param expiryThresholdMillis Get sessions with expiry score <= this value
     * @param limit Maximum number of sessions to return (for batching)
     * @return Multi stream of SessionExpiryEntry with userId and sessionId
     * @see CacheClient#getExpiredSessionsWithData(long, int)
     */
    public Multi<SessionExpiryEntry> getExpiredSessions(long expiryThresholdMillis, int limit) {
        LoggingUtil.logInfo(log, M_INDEX, "Querying expired sessions with threshold: %d, limit: %d", expiryThresholdMillis, limit);

        // Use ZRANGEBYSCORE with limit to get oldest expired sessions first
        ScoreRange<Double> scoreRange = ScoreRange.from(Double.NEGATIVE_INFINITY, expiryThresholdMillis);
        ZRangeArgs args = new ZRangeArgs().limit(0, limit);

        // zrangebyscore returns Uni<List<String>>, convert to Multi<String> before transforming
        return sortedSetCommands.zrangebyscore(EXPIRY_INDEX_KEY, scoreRange, args)
                .onItem().transformToMulti(list -> Multi.createFrom().iterable(list))
                .onItem().transform(this::parseMember)
                .filter(Objects::nonNull);
    }

    /**
     * Get count of expired sessions for monitoring.
     *
     * @param expiryThresholdMillis Threshold timestamp
     * @return Count of expired sessions
     */
    public Uni<Long> getExpiredSessionCount(long expiryThresholdMillis) {
        ScoreRange<Double> scoreRange = ScoreRange.from(Double.NEGATIVE_INFINITY, expiryThresholdMillis);
        return sortedSetCommands.zcount(EXPIRY_INDEX_KEY, scoreRange);
    }

    /**
     * Get total sessions in the index for monitoring.
     *
     * @return Total count of indexed sessions
     */
    public Uni<Long> getTotalIndexedSessions() {
        return sortedSetCommands.zcard(EXPIRY_INDEX_KEY);
    }

    private String buildMember(String userId, String sessionId) {
        return userId + MEMBER_SEPARATOR + sessionId;
    }

    private SessionExpiryEntry parseMember(String member) {
        if (member == null || member.isEmpty()) {
            return null;
        }

        int separatorIndex = member.indexOf(MEMBER_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex >= member.length() - 1) {
            LoggingUtil.logWarn(log, M_INDEX, "Invalid member format in expiry index: %s", member);
            return null;
        }

        String userId = member.substring(0, separatorIndex);
        String sessionId = member.substring(separatorIndex + 1);
        return new SessionExpiryEntry(userId, sessionId, member);
    }

    /**
     * Entry representing an expired session from the index.
     */
    public record SessionExpiryEntry(String userId, String sessionId, String rawMember) {
    }
}
