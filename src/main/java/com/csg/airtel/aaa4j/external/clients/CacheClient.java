package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.constant.ResponseCodeEnum;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;

import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;


import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;



@ApplicationScoped
public class CacheClient {

    //todo 1000tps handling need to check any overhead methods

    private static final Logger log = Logger.getLogger(CacheClient.class);
    final ReactiveRedisDataSource reactiveRedisDataSource;
    final ObjectMapper objectMapper;
    private static final String KEY_PREFIX = "user:";
    private final ReactiveValueCommands<String, String> valueCommands;
    private final SessionExpiryIndex sessionExpiryIndex;

    @Inject
    public CacheClient(ReactiveRedisDataSource reactiveRedisDataSource,
                       ObjectMapper objectMapper,
                       SessionExpiryIndex sessionExpiryIndex) {
        this.reactiveRedisDataSource = reactiveRedisDataSource;
        this.objectMapper = objectMapper;
        this.valueCommands = reactiveRedisDataSource.value(String.class, String.class);
        this.sessionExpiryIndex = sessionExpiryIndex;
    }

    /**
     * Store user data in Redis
     */
    public Uni<Void> storeUserData(String userId, UserSessionData userData) {
        long startTime = System.currentTimeMillis();
        log.infof("Storing user data  for  cache userId: %s", userId);
        String key = KEY_PREFIX + userId;
        String jsonValue = serialize(userData);
        Uni<Void> result = reactiveRedisDataSource.value(String.class)
                .set(key, jsonValue);
        log.infof("User data stored Complete for userId: %s in %d ms", userId, (System.currentTimeMillis() - startTime));
        return result;
    }

    /**
     * Retrieve user data from Redis
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 2,
            delay = 100,
            maxDuration = 5000
    )
    @Timeout(value = 5000)
    public Uni<UserSessionData> getUserData(String userId) {
        long startTime = System.currentTimeMillis();
        log.infof("Retrieving user data for cache userId: %s", userId);
        String key = KEY_PREFIX + userId;
        return reactiveRedisDataSource.value(String.class)
                .get(key)
                .onItem().transform(Unchecked.function(jsonValue -> {
                    if (jsonValue == null || jsonValue.isEmpty()) {
                        return null; // No record found
                    }
                    try {
                        UserSessionData userSessionData = objectMapper.readValue(jsonValue, UserSessionData.class);
                        log.infof("User data retrieved Complete for userId: %s in %d ms", userId, (System.currentTimeMillis() - startTime));
                        return userSessionData;
                    } catch (Exception e) {
                        throw new BaseException("Failed to deserialize user data", ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(), Response.Status.INTERNAL_SERVER_ERROR,ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(), e.getStackTrace());
                    }
                }))
                .onFailure().invoke(e -> log.error("Failed to get user data for userId: " + "10001", e));
    }


    public Uni<Void> updateUserAndRelatedCaches(String userId, UserSessionData userData) {
        long startTime = System.currentTimeMillis();
        log.infof("Updating user data and related caches for userId: %s", userId);
        String userKey = KEY_PREFIX + userId;

        return Uni.createFrom().item(() -> serialize(userData))
                .onItem().invoke(json -> log.infof("Updating cache for user {}: {}", userId, json))
                .onItem().transformToUni(serializedData ->
                        reactiveRedisDataSource.value(String.class)
                                .set(userKey, serializedData, new SetArgs().ex(Duration.ofHours(1000)))
                )
                .onItem().invoke(() -> log.infof("Cache update complete for userId: %s in %d ms", userId, (System.currentTimeMillis() - startTime)))
                .onFailure().invoke(err -> log.error("Failed to update cache for user {}", userId, err))
                .replaceWithVoid();
    }

    public Uni<String> deleteKey(String key) {
        String userKey = KEY_PREFIX + key;
        ReactiveKeyCommands<String> keyCommands = reactiveRedisDataSource.key();

        return keyCommands.del(userKey)
                .map(deleted -> deleted > 0
                        ? "Key deleted: " + key
                        : "Key not found: " + key);
    }

    private String serialize(UserSessionData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new BaseException("Failed to deserialize user data", ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(), Response.Status.INTERNAL_SERVER_ERROR,ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(), e.getStackTrace());

        }
    }

    /**
     * Get user session data as a map for a batch of user IDs using MGET.
     * Returns a map of userId -> UserSessionData for efficient lookups.
     *
     * @param userIds list of user IDs to retrieve
     * @return Uni with Map of userId -> UserSessionData
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 2,
            delay = 100,
            maxDuration = 5000
    )
    @Timeout(value = 10000)
    public Uni<Map<String, UserSessionData>> getUserDataBatchAsMap(java.util.List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Uni.createFrom().item(new HashMap<>());
        }

        log.infof("Retrieving batch user data as map for %d users using MGET", userIds.size());
        long startTime = System.currentTimeMillis();

        // Build keys with prefix
        String[] keys = userIds.stream()
                .map(id -> KEY_PREFIX + id)
                .toArray(String[]::new);

        // Use MGET for single network round trip
        return valueCommands.mget(keys)
                .onItem().transform(resultMap -> {
                    log.infof("MGET completed for %d keys in %d ms", keys.length, System.currentTimeMillis() - startTime);

                    Map<String, UserSessionData> userDataMap = new HashMap<>();
                    for (Map.Entry<String, String> entry : resultMap.entrySet()) {
                        if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                            try {
                                // Strip prefix from key to get userId
                                String userId = entry.getKey().substring(KEY_PREFIX.length());
                                UserSessionData userData = objectMapper.readValue(entry.getValue(), UserSessionData.class);
                                userDataMap.put(userId, userData);
                            } catch (Exception e) {
                                log.warnf("Failed to deserialize user data for key %s: %s", entry.getKey(), e.getMessage());
                            }
                        }
                    }
                    return userDataMap;
                });
    }

    /**
     * Get expired sessions with their associated user data in a single operation.
     * Combines SessionExpiryIndex lookup with batch user data retrieval.
     *
     * @param expiryThresholdMillis Get sessions with expiry score <= this value
     * @param limit Maximum number of sessions to return (for batching)
     * @return Uni with ExpiredSessionsWithData containing entries and user data map
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 2,
            delay = 100,
            maxDuration = 5000
    )
    @Timeout(value = 10000)
    public Uni<ExpiredSessionsWithData> getExpiredSessionsWithData(long expiryThresholdMillis, int limit) {
        log.infof("Retrieving expired sessions with data, threshold: %d, limit: %d",
                expiryThresholdMillis, limit);
        long startTime = System.currentTimeMillis();

        return sessionExpiryIndex.getExpiredSessions(expiryThresholdMillis, limit)
                .collect().asList()
                .onItem().transformToUni(expiredEntries -> {
                    if (expiredEntries.isEmpty()) {
                        log.debug("No expired sessions found");
                        return Uni.createFrom().item(
                                new ExpiredSessionsWithData(expiredEntries, new HashMap<>()));
                    }

                    // Extract unique user IDs for batch retrieval
                    List<String> userIds = expiredEntries.stream()
                            .map(SessionExpiryIndex.SessionExpiryEntry::userId)
                            .distinct()
                            .toList();

                    log.infof("Found %d expired sessions for %d users",
                            expiredEntries.size(), userIds.size());

                    // Batch fetch user data using MGET
                    return getUserDataBatchAsMap(userIds)
                            .onItem().transform(userDataMap -> {
                                log.infof("Retrieved expired sessions with data in %d ms",
                                        System.currentTimeMillis() - startTime);
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