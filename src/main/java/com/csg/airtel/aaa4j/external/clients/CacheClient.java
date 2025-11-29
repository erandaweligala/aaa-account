package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.constant.ResponseCodeEnum;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
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


@ApplicationScoped
public class CacheClient {

    private static final Logger log = Logger.getLogger(CacheClient.class);
    private static final String KEY_PREFIX = "user:";

    // Circuit Breaker Configuration
    private static final int CIRCUIT_BREAKER_REQUEST_VOLUME_THRESHOLD = 10;
    private static final double CIRCUIT_BREAKER_FAILURE_RATIO = 0.5;
    private static final long CIRCUIT_BREAKER_DELAY_MS = 5000;
    private static final int CIRCUIT_BREAKER_SUCCESS_THRESHOLD = 2;

    // Retry Configuration
    private static final int RETRY_MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 100;
    private static final long RETRY_MAX_DURATION_MS = 5000;

    // Timeout Configuration
    private static final long TIMEOUT_MS = 5000;

    // Cache Expiration
    private static final long CACHE_EXPIRATION_HOURS = 1000;

    final ReactiveRedisDataSource reactiveRedisDataSource;
    final ObjectMapper objectMapper;

    @Inject
    public CacheClient(ReactiveRedisDataSource reactiveRedisDataSource, ObjectMapper objectMapper) {
        this.reactiveRedisDataSource = reactiveRedisDataSource;
        this.objectMapper = objectMapper;
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
            requestVolumeThreshold = CIRCUIT_BREAKER_REQUEST_VOLUME_THRESHOLD,
            failureRatio = CIRCUIT_BREAKER_FAILURE_RATIO,
            delay = CIRCUIT_BREAKER_DELAY_MS,
            successThreshold = CIRCUIT_BREAKER_SUCCESS_THRESHOLD
    )
    @Retry(
            maxRetries = RETRY_MAX_RETRIES,
            delay = RETRY_DELAY_MS,
            maxDuration = RETRY_MAX_DURATION_MS
    )
    @Timeout(value = TIMEOUT_MS)
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
                .onFailure().invoke(e -> log.error("Failed to get user data for userId: " + userId, e));
    }


    public Uni<Void> updateUserAndRelatedCaches(String userId, UserSessionData userData) {
        long startTime = System.currentTimeMillis();
        log.infof("Updating user data and related caches for userId: %s", userId);
        String userKey = KEY_PREFIX + userId;

        return Uni.createFrom().item(() -> serialize(userData))
                .onItem().invoke(json -> log.infof("Updating cache for user {}: {}", userId, json))
                .onItem().transformToUni(serializedData ->
                        reactiveRedisDataSource.value(String.class)
                                .set(userKey, serializedData, new SetArgs().ex(Duration.ofHours(CACHE_EXPIRATION_HOURS)))
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

}