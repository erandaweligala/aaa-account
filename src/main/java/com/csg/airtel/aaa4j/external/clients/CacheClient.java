package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.constant.ResponseCodeEnum;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@ApplicationScoped
public class CacheClient {

    private static final Logger log = Logger.getLogger(CacheClient.class);
    final ReactiveRedisDataSource reactiveRedisDataSource;
    final ObjectMapper objectMapper;
    private static final String KEY_PREFIX = "user:";
    private final ReactiveValueCommands<String, String> valueCommands;
    private final SessionExpiryIndex sessionExpiryIndex;

    private final ReactiveKeyCommands<String> keyCommands;

    @Inject
    public CacheClient(ReactiveRedisDataSource reactiveRedisDataSource,
                       ObjectMapper objectMapper,
                       SessionExpiryIndex sessionExpiryIndex) {
        this.reactiveRedisDataSource = reactiveRedisDataSource;
        this.objectMapper = objectMapper;
        this.valueCommands = reactiveRedisDataSource.value(String.class, String.class);
        this.keyCommands = reactiveRedisDataSource.key();
        this.sessionExpiryIndex = sessionExpiryIndex;
    }

    /**
     * Store user data in Redis.
     * Cache entries persist indefinitely without TTL expiration.
     * Session cleanup is managed separately via IdleSessionTerminatorScheduler.
     */
    @CircuitBreaker(
            requestVolumeThreshold = 500,   // Optimized for 5000 TPS
            failureRatio = 0.75,
            delay = 2000,                   // Faster recovery
            successThreshold = 3
    )
    @Retry(
            maxRetries = 1,
            delay = 30,                     // Optimized for 5000 TPS
            maxDuration = 2000              // Fail faster
    )
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    public Uni<Void> storeUserData(String userId, UserSessionData userData) {
        if (log.isDebugEnabled()) {
            log.debugf("Storing user data for cache userId: %s", userId);
        }
        String key = KEY_PREFIX + userId;

        try {
            // Use cached valueCommands for better performance at high TPS
            String jsonValue = objectMapper.writeValueAsString(userData);
            return valueCommands.set(key, jsonValue);
        } catch (Exception e) {
            log.errorf("Failed to serialize user data for userId: %s - %s", userId, e.getMessage());
            return Uni.createFrom().failure(new BaseException(
                    "Failed to serialize user data",
                    ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(),
                    Response.Status.INTERNAL_SERVER_ERROR,
                    ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(),
                    null
            ));
        }
    }

    /**
     * Retrieve user data from Redis.
     */
    @CircuitBreaker(
            requestVolumeThreshold = 500,   // Optimized for 5000 TPS
            failureRatio = 0.75,
            delay = 2000,                   // Faster recovery
            successThreshold = 3
    )
    @Retry(
            maxRetries = 1,
            delay = 30,                     // Optimized for 5000 TPS
            maxDuration = 2000
    )
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    public Uni<UserSessionData> getUserData(String userId) {
        final long startTime = log.isDebugEnabled() ? System.currentTimeMillis() : 0;
        if (log.isDebugEnabled()) {
            log.debugf("Retrieving user data for cache userId: %s", userId);
        }
        String key = KEY_PREFIX + userId;

        // Use cached valueCommands for better performance at high TPS
        return valueCommands.get(key)
                .onItem().ifNotNull().transform(json -> {
                    try {
                        UserSessionData userData = objectMapper.readValue(json, UserSessionData.class);

                        if (log.isDebugEnabled()) {
                            log.debugf("User data retrieved for userId: %s in %d ms",
                                    userId, (System.currentTimeMillis() - startTime));
                        }

                        return userData;
                    } catch (Exception e) {
                        log.errorf("Failed to deserialize user data for userId: %s - %s", userId, e.getMessage());
                        throw new BaseException(
                                "Failed to deserialize user data",
                                ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(),
                                Response.Status.INTERNAL_SERVER_ERROR,
                                ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(),
                                null
                        );
                    }
                })
                .onFailure().invoke(e -> log.errorf("Failed to get user data for userId: %s", userId, e));
    }


    /**
     * Update user data and related caches in Redis.
     */
    @CircuitBreaker(
            requestVolumeThreshold = 500,   // Optimized for 5000 TPS
            failureRatio = 0.75,
            delay = 2000,                   // Faster recovery
            successThreshold = 3
    )
    @Retry(
            maxRetries = 1,
            delay = 30,                     // Optimized for 5000 TPS
            maxDuration = 2000
    )
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    public Uni<Void> updateUserAndRelatedCaches(String userId, UserSessionData userData) {
        if (log.isDebugEnabled()) {
            log.debugf("Updating user data and related caches for userId: %s", userId);
        }
        String userKey = KEY_PREFIX + userId;

        try {
            // Use cached valueCommands for better performance at high TPS
            String jsonValue = objectMapper.writeValueAsString(userData);
            return valueCommands.set(userKey, jsonValue)
                    .onFailure().invoke(err -> log.errorf("Failed to update cache for user %s", userId, err))
                    .replaceWithVoid();
        } catch (Exception e) {
            log.errorf("Failed to serialize user data for userId: %s - %s", userId, e.getMessage());
            return Uni.createFrom().failure(new BaseException(
                    "Failed to serialize user data",
                    ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(),
                    Response.Status.INTERNAL_SERVER_ERROR,
                    ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(),
                    null
            ));
        }
    }


    /**
     *  Use cached keyCommands instead of creating new instance
     */
    public Uni<String> deleteKey(String key) {
        String userKey = KEY_PREFIX + key;

        return keyCommands.del(userKey)
                .map(deleted -> deleted > 0
                        ? "Key deleted: " + key
                        : "Key not found: " + key);
    }

    /**
     *
     * @param userIds list of user IDs to retrieve
     * @return Uni with Map of userId -> UserSessionData
     */
    @CircuitBreaker(
            requestVolumeThreshold = 500,   // Optimized for 5000 TPS
            failureRatio = 0.75,
            delay = 2000,                   // Faster recovery
            successThreshold = 3
    )
    @Retry(
            maxRetries = 1,
            delay = 30,                     // Optimized for 5000 TPS
            maxDuration = 2000
    )
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)  // Batch operations need slightly more time
    public Uni<Map<String, UserSessionData>> getUserDataBatchAsMap(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }

        final int size = userIds.size();
        if (log.isDebugEnabled()) {
            log.debugf("Retrieving batch user data as map for %d users using MGET", size);
        }

        // Build keys with prefix - optimized with pre-sized array
        String[] keys = new String[size];
        for (int i = 0; i < size; i++) {
            keys[i] = KEY_PREFIX + userIds.get(i);
        }

        // Use MGET for single network round trip
        return valueCommands.mget(keys)
                .onItem().transform(resultMap -> {

                    // Pre-size HashMap: capacity = size / 0.75 load factor + 1
                    Map<String, UserSessionData> userDataMap = HashMap.newHashMap((int) (size / 0.75) + 1);
                    for (Map.Entry<String, String> entry : resultMap.entrySet()) {
                        String value = entry.getValue();
                        if (value != null && !value.isEmpty()) {
                            try {
                                // Strip prefix from key to get userId
                                String userId = entry.getKey().substring(KEY_PREFIX.length());
                                UserSessionData userData = objectMapper.readValue(value, UserSessionData.class);
                                userDataMap.put(userId, userData);
                            } catch (Exception e) {
                                log.errorf("Failed to deserialize user data for key %s: %s", entry.getKey(), e.getMessage());
                            }
                        }
                    }
                    return userDataMap;
                });
    }

    /**
     * Store multiple user data entries in Redis using MSET for high-performance batch writes.
     * Critical for bulk operations at 5000 TPS.
     *
     * @param userDataMap Map of userId -> UserSessionData to store
     * @return Uni<Void> completing when all entries are stored
     */
    @CircuitBreaker(
            requestVolumeThreshold = 500,   // Optimized for 5000 TPS
            failureRatio = 0.75,
            delay = 2000,
            successThreshold = 3
    )
    @Retry(
            maxRetries = 1,
            delay = 30,
            maxDuration = 2000
    )
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)  // Batch operations need slightly more time
    public Uni<Void> storeUserDataBatch(Map<String, UserSessionData> userDataMap) {
        if (userDataMap == null || userDataMap.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        final int size = userDataMap.size();
        if (log.isDebugEnabled()) {
            log.debugf("Storing batch user data for %d users using MSET", size);
        }

        try {
            // Serialize all user data with pre-sized map
            Map<String, String> serializedData = HashMap.newHashMap((int) (size / 0.75) + 1);
            for (Map.Entry<String, UserSessionData> entry : userDataMap.entrySet()) {
                String key = KEY_PREFIX + entry.getKey();
                String jsonValue = objectMapper.writeValueAsString(entry.getValue());
                serializedData.put(key, jsonValue);
            }

            // Use MSET for single network round trip
            return valueCommands.mset(serializedData)
                    .onFailure().invoke(err ->
                            log.errorf("Failed to batch store %d users: %s", size, err.getMessage()))
                    .replaceWithVoid();

        } catch (Exception e) {
            log.errorf("Failed to serialize batch user data: %s", e.getMessage());
            return Uni.createFrom().failure(new BaseException(
                    "Failed to serialize batch user data",
                    ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(),
                    Response.Status.INTERNAL_SERVER_ERROR,
                    ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(),
                    null
            ));
        }
    }


    /**
     * Expired session retrieval with optimized fault tolerance
     */
    @CircuitBreaker(
            requestVolumeThreshold = 500,  // Optimized for 5000 TPS
            failureRatio = 0.75,
            delay = 2000,                  // Faster recovery
            successThreshold = 3
    )
    @Retry(
            maxRetries = 1,
            delay = 30,                    // Optimized for 5000 TPS
            maxDuration = 3000
    )
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)  // Complex operations need slightly more time
    public Uni<ExpiredSessionsWithData> getExpiredSessionsWithData(long expiryThresholdMillis, int limit) {
        final long startTime = log.isDebugEnabled() ? System.currentTimeMillis() : 0;
        if (log.isDebugEnabled()) {
            log.debugf("Retrieving expired sessions with data, threshold: %d, limit: %d",
                    expiryThresholdMillis, limit);
        }

        return sessionExpiryIndex.getExpiredSessions(expiryThresholdMillis, limit)
                .collect().asList()
                .onItem().transformToUni(expiredEntries -> {
                    if (expiredEntries.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            log.debug("No expired sessions found");
                        }
                        return Uni.createFrom().item(
                                new ExpiredSessionsWithData(expiredEntries, Map.of()));
                    }

                    // Extract unique user IDs for batch retrieval - optimized with pre-sized set
                    int entryCount = expiredEntries.size();
                    java.util.Set<String> uniqueUserIds = HashSet.newHashSet((int) (entryCount / 0.75) + 1);
                    for (SessionExpiryIndex.SessionExpiryEntry entry : expiredEntries) {
                        uniqueUserIds.add(entry.userId());
                    }
                    List<String> userIds = new java.util.ArrayList<>(uniqueUserIds);

                    if (log.isDebugEnabled()) {
                        log.debugf("Found %d expired sessions for %d users",
                                entryCount, userIds.size());
                    }

                    // Batch fetch user data using MGET
                    return getUserDataBatchAsMap(userIds)
                            .onItem().transform(userDataMap -> {
                                if (log.isDebugEnabled()) {
                                    log.debugf("Retrieved expired sessions with data in %d ms",
                                            System.currentTimeMillis() - startTime);
                                }
                                return new ExpiredSessionsWithData(expiredEntries, userDataMap);
                            });
                });
    }

    /**
     * Result containing expired session entries and their associated user data.
     *
     * @param expiredEntries List of expired session entries from the index
     * @param userDataMap Map of userId to UserSessionData for efficient lookup
     */
    public record ExpiredSessionsWithData(
            List<SessionExpiryIndex.SessionExpiryEntry> expiredEntries,
            Map<String, UserSessionData> userDataMap) {

        /**
         * Get the user data for a specific user ID.
         *
         * @param userId The user ID to look up
         * @return The UserSessionData or null if not found
         */
        public UserSessionData getUserData(String userId) {
            return userDataMap.get(userId);
        }

        /**
         * Get expired entries grouped by user ID.
         *
         * @return Map of userId to list of expired session entries
         */
        public Map<String, List<SessionExpiryIndex.SessionExpiryEntry>> getEntriesByUser() {
            return expiredEntries.stream()
                    .collect(Collectors.groupingBy(SessionExpiryIndex.SessionExpiryEntry::userId));
        }

        /**
         * Get raw members for index cleanup.
         *
         * @return List of raw member strings for removal from index
         */
        public List<String> getRawMembers() {
            return expiredEntries.stream()
                    .map(SessionExpiryIndex.SessionExpiryEntry::rawMember)
                    .toList();
        }
    }

}