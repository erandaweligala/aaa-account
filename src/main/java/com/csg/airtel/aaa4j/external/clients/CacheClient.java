package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
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
    private static final String M_GET = "getUserData";
    private static final String M_STORE = "storeUserData";
    private static final String M_UPDATE = "updateCache";
    private static final String M_BATCH = "batchGet";

    final ReactiveRedisDataSource reactiveRedisDataSource;
    final ObjectMapper objectMapper;
    private static final String KEY_PREFIX = "user:";
    private static final String GROUP_KEY_PREFIX = "group:";
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
    @Retry(
            maxRetries = 1,
            delay = 100,
            jitter = 50
    )
    @Timeout(value = 8, unit = ChronoUnit.SECONDS)                  // Reduced from 2000ms - faster timeout
    public Uni<String> getGroupId(String userId) {

        LoggingUtil.logDebug(log, M_GET, "Retrieving Group id for cache userId: %s", userId);
        String key = GROUP_KEY_PREFIX + userId;

        // Use cached valueCommands for better performance at high TPS
        return valueCommands.get(key)
                .onItem().ifNotNull().transform(Unchecked.function(groupId -> {
                    try {

                        LoggingUtil.logDebug(log, M_GET, "User data retrieved for userId: %s",
                                userId);

                        return groupId;
                    } catch (Exception e) {
                        LoggingUtil.logError(log, M_GET, null, "Failed to deserialize user data for userId: %s - %s", userId, e.getMessage());
                        throw new BaseException(
                                "Failed to deserialize user data",
                                ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(),
                                Response.Status.INTERNAL_SERVER_ERROR,
                                ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(),
                                null
                        );
                    }
                }))
                .onFailure().invoke(e -> LoggingUtil.logError(log, M_GET, e, "Failed to get user data for userId: %s", userId));
    }


    @Retry(
            maxRetries = 1,
            delay = 30,
            maxDuration = 1500
    )
    @Timeout(value = 8, unit = ChronoUnit.SECONDS)
    public Uni<Void> storeUserData(String userId, UserSessionData userData, String userName) {

        LoggingUtil.logDebug(log, M_STORE, "Storing user data for cache userId: %s", userId);

        String key = KEY_PREFIX + userId;

        return Uni.createFrom().item(userData)
                .onItem().transformToUni(data -> {
                    try {
                        String jsonValue = objectMapper.writeValueAsString(data);

                        // Always store user data
                        Uni<?> storeUserDataUni = valueCommands.set(key, jsonValue);

                        // No group handling required
                        if (data == null || data.getGroupId() == null
                                || "1".equalsIgnoreCase(data.getGroupId())) {
                            return storeUserDataUni.replaceWithVoid();
                        }

                        String groupKey = GROUP_KEY_PREFIX + userName;
                        String groupValues = data.getGroupId() + "," +
                                data.getConcurrency() + "," +
                                data.getUserStatus() + "," +
                                data.getSessionTimeOut();

                        // Check cache first
                        return valueCommands.get(groupKey)
                                .onItem().transformToUni(existingValue -> {
                                    if (existingValue == null) {
                                        // Group cache missing → update it
                                        return Uni.combine().all().unis(
                                                valueCommands.set(groupKey, groupValues),
                                                storeUserDataUni
                                        ).discardItems();
                                    }

                                    // Group cache already exists → only store user data
                                    return storeUserDataUni.replaceWithVoid();
                                });

                    } catch (Exception e) {
                        LoggingUtil.logError(log, M_STORE, null, "Failed to serialize user data for userId: %s - %s",
                                userId, e.getMessage());

                        return Uni.createFrom().failure(new BaseException(
                                "Failed to serialize user data",
                                ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(),
                                Response.Status.INTERNAL_SERVER_ERROR,
                                ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(),
                                null
                        ));
                    }
                });
    }


    /**
     * Retrieve user data from Redis.
     */
    @Retry(
            maxRetries = 1,
            delay = 100,
            jitter = 50
    )
    @Timeout(value = 8, unit = ChronoUnit.SECONDS)                  // Reduced from 2000ms - faster timeout
    public Uni<UserSessionData> getUserData(String userId) {
        final long startTime = log.isDebugEnabled() ? System.currentTimeMillis() : 0;
        LoggingUtil.logDebug(log, M_GET, "Retrieving user data for cache userId: %s", userId);
        String key = KEY_PREFIX + userId;

        // Use cached valueCommands for better performance at high TPS
        return valueCommands.get(key)
                .onItem().ifNotNull().transform(Unchecked.function(json -> {
                    try {
                        UserSessionData userData = objectMapper.readValue(json, UserSessionData.class);

                        LoggingUtil.logDebug(log, M_GET, "User data retrieved for userId: %s in %d ms",
                                userId, (System.currentTimeMillis() - startTime));

                        return userData;
                    } catch (Exception e) {
                        LoggingUtil.logError(log, M_GET, null, "Failed to deserialize user data for userId: %s - %s", userId, e.getMessage());
                        throw new BaseException(
                                "Failed to deserialize user data",
                                ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description(),
                                Response.Status.INTERNAL_SERVER_ERROR,
                                ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code(),
                                null
                        );
                    }
                }))
                .onFailure().invoke(e -> LoggingUtil.logError(log, M_GET, e, "Failed to get user data for userId: %s", userId));
    }


    /**
     * Update user data and related caches in Redis.
     */
    @Retry(
            maxRetries = 1,
            delay = 30,                     // Reduced from 50ms - faster retry
            maxDuration = 1500              // Reduced from 2000ms - fail faster
    )
    @Timeout(value = 8, unit = ChronoUnit.SECONDS)
    public Uni<Void> updateUserAndRelatedCaches(String userId, UserSessionData userData,String userName) {
        LoggingUtil.logDebug(log, M_UPDATE, "Updating user data and related caches for userId: %s", userId);
        String userKey = KEY_PREFIX + userId;

        try {
            // Use cached valueCommands for better performance at high TPS
            String jsonValue = objectMapper.writeValueAsString(userData);

            // Run group and user SET operations in parallel for zero overhead
            if(userData != null && userData.getGroupId() != null && !userData.getGroupId().equalsIgnoreCase("1")) {
                String groupKey = GROUP_KEY_PREFIX + userName;
                String groupValues = userData.getGroupId()+","+userData.getConcurrency()+","+userData.getUserStatus()+","+userData.getSessionTimeOut();

                // Combine both SET operations in parallel - reduces RTT by executing concurrently
                return Uni.combine().all().unis(
                        valueCommands.set(groupKey, groupValues),
                        valueCommands.set(userKey, jsonValue)
                ).discardItems()
                        .onFailure().invoke(err -> LoggingUtil.logError(log, M_UPDATE, err, "Failed to update cache for user %s", userId));
            }

            // If no groupId, only update user data
            return valueCommands.set(userKey, jsonValue)
                    .onFailure().invoke(err -> LoggingUtil.logError(log, M_UPDATE, err, "Failed to update cache for user %s", userId))
                    .replaceWithVoid();
        } catch (Exception e) {
            LoggingUtil.logError(log, M_UPDATE, null, "Failed to serialize user data for userId: %s - %s", userId, e.getMessage());
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
        String userKey1 = KEY_PREFIX + key;
        String userKey2 = GROUP_KEY_PREFIX + key;

        return keyCommands.del(userKey1, userKey2)
                .map(deleted -> deleted > 0
                        ? "Keys deleted count: " + deleted
                        : "No keys found");
    }

    /**
     *
     * @param userIds list of user IDs to retrieve
     * @return Uni with Map of userId -> UserSessionData
     */

    @Retry(
            maxRetries = 1,
            delay = 50,                     // Reduced from 100ms - faster retry
            maxDuration = 3000
    )
    @Timeout(value = 8, unit = ChronoUnit.SECONDS)
    public Uni<Map<String, UserSessionData>> getUserDataBatchAsMap(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }

        final int size = userIds.size();
        LoggingUtil.logDebug(log, M_BATCH, "Retrieving batch user data as map for %d users using MGET", size);

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
                                LoggingUtil.logError(log, M_BATCH, null, "Failed to deserialize user data for key %s: %s", entry.getKey(), e.getMessage());
                            }
                        }
                    }
                    return userDataMap;
                });
    }


    /**
     * Expired session retrieval with optimized fault tolerance
     */
    @CircuitBreaker(
            requestVolumeThreshold = 200,  // Increased from 100 for 2000 TPS
            failureRatio = 0.75,            // Increased from 0.7 - less sensitive
            delay = 3000,                   // Reduced from 5000 - faster recovery
            successThreshold = 3            // Increased from 2 - more stable
    )
    @Retry(
            maxRetries = 1,
            delay = 50,                     // Reduced from 100ms - faster retry
            maxDuration = 4000
    )
    @Timeout(value = 8000)
    public Uni<ExpiredSessionsWithData> getExpiredSessionsWithData(long expiryThresholdMillis, int limit) {
        final long startTime = log.isDebugEnabled() ? System.currentTimeMillis() : 0;
        LoggingUtil.logDebug(log, M_BATCH, "Retrieving expired sessions with data, threshold: %d, limit: %d",
                expiryThresholdMillis, limit);

        return sessionExpiryIndex.getExpiredSessions(expiryThresholdMillis, limit)
                .collect().asList()
                .onItem().transformToUni(expiredEntries -> {
                    if (expiredEntries.isEmpty()) {
                        LoggingUtil.logDebug(log, M_BATCH, "No expired sessions found");
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

                    LoggingUtil.logDebug(log, M_BATCH, "Found %d expired sessions for %d users",
                            entryCount, userIds.size());

                    // Batch fetch user data using MGET
                    return getUserDataBatchAsMap(userIds)
                            .onItem().transform(userDataMap -> {
                                LoggingUtil.logDebug(log, M_BATCH, "Retrieved expired sessions with data in %d ms",
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
