package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.constant.ResponseCodeEnum;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Multi;
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
import java.util.Map;
import java.util.stream.Collectors;


@ApplicationScoped
public class CacheClient {

    private static final Logger log = Logger.getLogger(CacheClient.class);
    final ReactiveRedisDataSource reactiveRedisDataSource;
    final ObjectMapper objectMapper;
    private static final String KEY_PREFIX = "user:";
    private final ReactiveValueCommands<String, String> valueCommands;

    @Inject
    public CacheClient(ReactiveRedisDataSource reactiveRedisDataSource, ObjectMapper objectMapper) {
        this.reactiveRedisDataSource = reactiveRedisDataSource;
        this.objectMapper = objectMapper;
        this.valueCommands = reactiveRedisDataSource.value(String.class, String.class);
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
     * Scan all user keys from Redis with pattern "user:*".
     * Uses SCAN command for efficient iteration without blocking Redis.
     * @return Multi stream of user keys (without the "user:" prefix)
     */
    public Multi<String> scanAllUserKeys() {
        log.info("Scanning all user keys from Redis cache");
        ReactiveKeyCommands<String> keyCommands = reactiveRedisDataSource.key();
        KeyScanArgs scanArgs = new KeyScanArgs().match(KEY_PREFIX + "*").count(100);

        return keyCommands.scan(scanArgs)
                .map(key -> key.substring(KEY_PREFIX.length()))
                .onFailure().invoke(e -> log.errorf("Failed to scan user keys: %s", e.getMessage()));
    }

    /**
     * Get all user session data for a batch of user IDs using MGET.
     * MGET is O(N) where N is the number of keys, but uses a single network round trip.
     * This is critical for 10M+ users - single round trip vs N round trips.
     *
     * @param userIds list of user IDs to retrieve
     * @return Multi stream of UserSessionData (non-null entries only)
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
    public Multi<UserSessionData> getUserDataBatch(java.util.List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Multi.createFrom().empty();
        }

        log.infof("Retrieving batch user data for %d users using MGET", userIds.size());
        long startTime = System.currentTimeMillis();

        // Build keys with prefix
        String[] keys = userIds.stream()
                .map(id -> KEY_PREFIX + id)
                .toArray(String[]::new);

        // Use MGET for single network round trip - critical for scaling
        return valueCommands.mget(keys)
                .onItem().transformToMulti(resultMap -> {
                    log.infof("MGET completed for %d keys in %d ms", keys.length, System.currentTimeMillis() - startTime);
                    return Multi.createFrom().iterable(resultMap.values());
                })
                .filter(jsonValue -> jsonValue != null && !jsonValue.isEmpty())
                .onItem().transform(Unchecked.function(jsonValue -> {
                    try {
                        return objectMapper.readValue(jsonValue, UserSessionData.class);
                    } catch (Exception e) {
                        log.warnf("Failed to deserialize user data: %s", e.getMessage());
                        return null;
                    }
                }))
                .filter(java.util.Objects::nonNull);
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

}