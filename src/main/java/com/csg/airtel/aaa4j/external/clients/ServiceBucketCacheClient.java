package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Redis cache client for caching service bucket data.
 * Reduces database load by caching frequently accessed user bucket information.
 */
@ApplicationScoped
public class ServiceBucketCacheClient {

    private static final Logger log = Logger.getLogger(ServiceBucketCacheClient.class);
    private static final String KEY_PREFIX = "service-bucket:";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final ObjectMapper objectMapper;
    private final Duration cacheTTL;

    @Inject
    public ServiceBucketCacheClient(
            ReactiveRedisDataSource reactiveRedisDataSource,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "service-bucket-cache.ttl-seconds", defaultValue = "300") int ttlSeconds) {
        this.valueCommands = reactiveRedisDataSource.value(String.class, String.class);
        this.keyCommands = reactiveRedisDataSource.key();
        this.objectMapper = objectMapper;
        this.cacheTTL = Duration.ofSeconds(ttlSeconds);

        log.infof("ServiceBucketCacheClient initialized with TTL: %d seconds", ttlSeconds);
    }

    /**
     * Store service buckets in Redis cache with TTL.
     *
     * @param userName the username to cache buckets for
     * @param serviceBuckets list of service bucket information
     * @return Uni<Void>
     */
    @Retry(
            maxRetries = 1,
            delay = 30,
            maxDuration = 1500
    )
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    public Uni<Void> cacheServiceBuckets(String userName, List<ServiceBucketInfo> serviceBuckets) {
        if (log.isDebugEnabled()) {
            log.debugf("Caching service buckets for user: %s, count: %d", userName, serviceBuckets.size());
        }

        String key = KEY_PREFIX + userName;

        try {
            String jsonValue = objectMapper.writeValueAsString(serviceBuckets);
            SetArgs setArgs = new SetArgs().ex(cacheTTL);

            return valueCommands.set(key, jsonValue, setArgs)
                    .onItem().invoke(() -> {
                        if (log.isDebugEnabled()) {
                            log.debugf("Successfully cached service buckets for user: %s", userName);
                        }
                    })
                    .onFailure().invoke(err ->
                        log.errorf("Failed to cache service buckets for user: %s - %s", userName, err.getMessage()))
                    .replaceWithVoid();
        } catch (Exception e) {
            log.errorf("Failed to serialize service buckets for user: %s - %s", userName, e.getMessage());
            return Uni.createFrom().voidItem();
        }
    }

    /**
     * Retrieve cached service buckets from Redis.
     *
     * @param userName the username to retrieve buckets for
     * @return Uni containing list of ServiceBucketInfo or null if not cached
     */
    @Retry(
            maxRetries = 1,
            delay = 50,
            jitter = 25
    )
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    public Uni<List<ServiceBucketInfo>> getCachedServiceBuckets(String userName) {
        if (log.isDebugEnabled()) {
            log.debugf("Retrieving cached service buckets for user: %s", userName);
        }

        String key = KEY_PREFIX + userName;

        return valueCommands.get(key)
                .onItem().transform(jsonValue -> {
                    if (jsonValue == null || jsonValue.isEmpty()) {
                        if (log.isDebugEnabled()) {
                            log.debugf("Cache miss for user: %s", userName);
                        }
                        return null;
                    }

                    try {
                        List<ServiceBucketInfo> buckets = objectMapper.readValue(
                                jsonValue,
                                new TypeReference<List<ServiceBucketInfo>>() {});

                        if (log.isDebugEnabled()) {
                            log.debugf("Cache hit for user: %s, count: %d", userName, buckets.size());
                        }
                        return buckets;
                    } catch (Exception e) {
                        log.errorf("Failed to deserialize service buckets for user: %s - %s",
                                userName, e.getMessage());
                        return null;
                    }
                })
                .onFailure().invoke(err ->
                    log.errorf("Failed to retrieve cached service buckets for user: %s - %s",
                            userName, err.getMessage()))
                .onFailure().recoverWithNull();
    }

    /**
     * Invalidate (delete) cached service buckets for a specific user.
     * Use this when user bucket data is updated.
     *
     * @param userName the username to invalidate cache for
     * @return Uni<Boolean> true if cache was deleted, false if not found
     */
    @Retry(
            maxRetries = 1,
            delay = 30,
            maxDuration = 1500
    )
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    public Uni<Boolean> invalidateCache(String userName) {
        if (log.isDebugEnabled()) {
            log.debugf("Invalidating cache for user: %s", userName);
        }

        String key = KEY_PREFIX + userName;

        return keyCommands.del(key)
                .onItem().transform(deleted -> {
                    boolean wasDeleted = deleted > 0;
                    if (log.isDebugEnabled()) {
                        log.debugf("Cache invalidation for user: %s - %s",
                                userName, wasDeleted ? "deleted" : "not found");
                    }
                    return wasDeleted;
                })
                .onFailure().invoke(err ->
                    log.errorf("Failed to invalidate cache for user: %s - %s", userName, err.getMessage()))
                .onFailure().recoverWithItem(false);
    }

    /**
     * Get or fetch service buckets with cache-aside pattern.
     * This method first checks cache, and if not found, executes the fallback function.
     *
     * @param userName the username to get buckets for
     * @param databaseFetcher function to fetch from database if cache misses
     * @return Uni containing list of ServiceBucketInfo
     */
    @CircuitBreaker(
            requestVolumeThreshold = 50,
            failureRatio = 0.6,
            delay = 5000,
            successThreshold = 3
    )
    public Uni<List<ServiceBucketInfo>> getOrFetchServiceBuckets(
            String userName,
            java.util.function.Supplier<Uni<List<ServiceBucketInfo>>> databaseFetcher) {

        return getCachedServiceBuckets(userName)
                .onItem().transformToUni(cachedBuckets -> {
                    if (cachedBuckets != null && !cachedBuckets.isEmpty()) {
                        // Cache hit - return cached data
                        if (log.isDebugEnabled()) {
                            log.debugf("Returning cached service buckets for user: %s", userName);
                        }
                        return Uni.createFrom().item(cachedBuckets);
                    }

                    // Cache miss - fetch from database and cache result
                    if (log.isDebugEnabled()) {
                        log.debugf("Cache miss - fetching from database for user: %s", userName);
                    }

                    return databaseFetcher.get()
                            .onItem().transformToUni(buckets -> {
                                if (buckets != null && !buckets.isEmpty()) {
                                    // Cache the result for next time
                                    return cacheServiceBuckets(userName, buckets)
                                            .replaceWith(buckets);
                                }
                                return Uni.createFrom().item(buckets);
                            });
                });
    }

    /**
     * Invalidate cache for multiple users in batch.
     *
     * @param userNames list of usernames to invalidate
     * @return Uni<Integer> count of successfully deleted cache entries
     */
    @Retry(
            maxRetries = 1,
            delay = 50,
            maxDuration = 2000
    )
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    public Uni<Integer> invalidateCacheBatch(List<String> userNames) {
        if (userNames == null || userNames.isEmpty()) {
            return Uni.createFrom().item(0);
        }

        log.infof("Batch invalidating cache for %d users", userNames.size());

        String[] keys = userNames.stream()
                .map(userName -> KEY_PREFIX + userName)
                .toArray(String[]::new);

        return keyCommands.del(keys)
                .onItem().invoke(deleted ->
                    log.infof("Batch cache invalidation completed - deleted: %d", deleted))
                .onFailure().invoke(err ->
                    log.errorf("Failed to batch invalidate cache - %s", err.getMessage()))
                .onFailure().recoverWithItem(0);
    }

    /**
     * Check if cache exists for a user.
     *
     * @param userName the username to check
     * @return Uni<Boolean> true if cache exists
     */
    public Uni<Boolean> exists(String userName) {
        String key = KEY_PREFIX + userName;
        return keyCommands.exists(key)
                .onItem().transform(count -> count > 0);
    }

    /**
     * Get remaining TTL for cached data.
     *
     * @param userName the username to check TTL for
     * @return Uni<Long> remaining TTL in seconds, -2 if key doesn't exist, -1 if no TTL
     */
    public Uni<Long> getRemainingTTL(String userName) {
        String key = KEY_PREFIX + userName;
        return keyCommands.ttl(key);
    }
}
