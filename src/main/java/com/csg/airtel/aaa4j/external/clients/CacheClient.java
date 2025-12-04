package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.constant.ResponseCodeEnum;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.service.FailoverPathLogger;
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
    final ReactiveRedisDataSource reactiveRedisDataSource;
    final ObjectMapper objectMapper;
    private static final String KEY_PREFIX = "user:";

    // Failover path tracking
    private final ThreadLocal<FailoverPathLogger.FailureCounter> storeCounter =
            ThreadLocal.withInitial(() -> new FailoverPathLogger.FailureCounter("CACHE_STORE"));
    private final ThreadLocal<FailoverPathLogger.FailureCounter> getCounter =
            ThreadLocal.withInitial(() -> new FailoverPathLogger.FailureCounter("CACHE_GET"));
    private final ThreadLocal<FailoverPathLogger.FailureCounter> updateCounter =
            ThreadLocal.withInitial(() -> new FailoverPathLogger.FailureCounter("CACHE_UPDATE"));
    private final ThreadLocal<FailoverPathLogger.FailureCounter> deleteCounter =
            ThreadLocal.withInitial(() -> new FailoverPathLogger.FailureCounter("CACHE_DELETE"));

    @Inject
    public CacheClient(ReactiveRedisDataSource reactiveRedisDataSource, ObjectMapper objectMapper) {
        this.reactiveRedisDataSource = reactiveRedisDataSource;
        this.objectMapper = objectMapper;
    }

    /**
     * Store user data in Redis
     */
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<Void> storeUserData(String userId, UserSessionData userData) {
        long startTime = System.currentTimeMillis();
        FailoverPathLogger.FailureCounter counter = storeCounter.get();
        int attemptCount = counter.incrementAttempt();

        FailoverPathLogger.logPrimaryPathAttempt(log, counter.getPath(), userId);
        log.infof("Storing user data  for  cache userId: %s", userId);
        String key = KEY_PREFIX + userId;
        String jsonValue = serialize(userData);
        Uni<Void> result = reactiveRedisDataSource.value(String.class)
                .set(key, jsonValue)
                .onItem().invoke(() -> {
                    if (counter.getFailureCount() > 0) {
                        FailoverPathLogger.logSuccessAfterFailure(log, counter.getPath(), attemptCount, userId);
                    }
                    counter.reset();
                    log.infof("User data stored Complete for userId: %s in %d ms", userId, (System.currentTimeMillis() - startTime));
                })
                .onFailure().invoke(e -> {
                    int failCount = counter.incrementFailure();
                    FailoverPathLogger.logFailoverAttempt(log, counter.getPath(), attemptCount, failCount, userId, e);
                });
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
        FailoverPathLogger.FailureCounter counter = getCounter.get();
        int attemptCount = counter.incrementAttempt();

        FailoverPathLogger.logPrimaryPathAttempt(log, counter.getPath(), userId);
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
                        if (counter.getFailureCount() > 0) {
                            FailoverPathLogger.logSuccessAfterFailure(log, counter.getPath(), attemptCount, userId);
                        }
                        counter.reset();
                        log.infof("User data retrieved Complete for userId: %s in %d ms", userId, (System.currentTimeMillis() - startTime));
                        return userSessionData;
                    } catch (Exception e) {
                        throw new BaseException("Failed to deserialize user data", ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(), Response.Status.INTERNAL_SERVER_ERROR,ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(), e.getStackTrace());
                    }
                }))
                .onFailure().invoke(e -> {
                    int failCount = counter.incrementFailure();
                    FailoverPathLogger.logFailoverAttempt(log, counter.getPath(), attemptCount, failCount, userId, e);
                    log.error("Failed to get user data for userId: " + userId, e);
                });
    }


    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<Void> updateUserAndRelatedCaches(String userId, UserSessionData userData) {
        long startTime = System.currentTimeMillis();
        FailoverPathLogger.FailureCounter counter = updateCounter.get();
        int attemptCount = counter.incrementAttempt();

        FailoverPathLogger.logPrimaryPathAttempt(log, counter.getPath(), userId);
        log.infof("Updating user data and related caches for userId: %s", userId);
        String userKey = KEY_PREFIX + userId;

        return Uni.createFrom().item(() -> serialize(userData))
                .onItem().invoke(json -> log.infof("Updating cache for user {}: {}", userId, json))
                .onItem().transformToUni(serializedData ->
                        reactiveRedisDataSource.value(String.class)
                                .set(userKey, serializedData, new SetArgs().ex(Duration.ofHours(1000)))
                )
                .onItem().invoke(() -> {
                    if (counter.getFailureCount() > 0) {
                        FailoverPathLogger.logSuccessAfterFailure(log, counter.getPath(), attemptCount, userId);
                    }
                    counter.reset();
                    log.infof("Cache update complete for userId: %s in %d ms", userId, (System.currentTimeMillis() - startTime));
                })
                .onFailure().invoke(err -> {
                    int failCount = counter.incrementFailure();
                    FailoverPathLogger.logFailoverAttempt(log, counter.getPath(), attemptCount, failCount, userId, err);
                    log.error("Failed to update cache for user {}", userId, err);
                })
                .replaceWithVoid();
    }

    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 100, maxDuration = 5000)
    @Timeout(value = 5000)
    public Uni<String> deleteKey(String key) {
        FailoverPathLogger.FailureCounter counter = deleteCounter.get();
        int attemptCount = counter.incrementAttempt();

        FailoverPathLogger.logPrimaryPathAttempt(log, counter.getPath(), key);
        String userKey = KEY_PREFIX + key;
        ReactiveKeyCommands<String> keyCommands = reactiveRedisDataSource.key();

        return keyCommands.del(userKey)
                .onItem().invoke(deleted -> {
                    if (counter.getFailureCount() > 0) {
                        FailoverPathLogger.logSuccessAfterFailure(log, counter.getPath(), attemptCount, key);
                    }
                    counter.reset();
                })
                .onFailure().invoke(e -> {
                    int failCount = counter.incrementFailure();
                    FailoverPathLogger.logFailoverAttempt(log, counter.getPath(), attemptCount, failCount, key, e);
                })
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