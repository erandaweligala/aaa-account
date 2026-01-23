package com.csg.airtel.aaa4j.external.clients;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * In-memory cache for username → groupId mappings.
 * Optimized for high TPS scenarios to avoid redundant cache lookups.
 *
 * This cache solves the problem of partial data access:
 * - When you have only username but need groupId
 * - Eliminates the need to fetch full UserSessionData just to extract groupId
 * - Provides O(1) in-memory lookup instead of Redis round-trip
 *
 * Cache Strategy:
 * - Auto-populated when UserSessionData is retrieved
 * - TTL of 30 minutes (mappings rarely change)
 * - Max 100K entries (adjust based on your user base)
 * - Thread-safe for concurrent access
 */
@ApplicationScoped
public class UserGroupMappingCache {

    private static final Logger log = Logger.getLogger(UserGroupMappingCache.class);

    // Marker value for users without a group
    private static final String NO_GROUP = "NO_GROUP_ASSIGNED";

    private final Cache<String, String> usernameToGroupIdCache;

    @Inject
    public UserGroupMappingCache() {
        this.usernameToGroupIdCache = Caffeine.newBuilder()
                .maximumSize(100_000)  // Adjust based on your user base
                .expireAfterWrite(Duration.ofMinutes(30))  // TTL: 30 minutes
                .recordStats()  // Enable statistics for monitoring
                .build();

        log.info("UserGroupMappingCache initialized with 100K capacity and 30min TTL");
    }

    /**
     * Get groupId for a username.
     *
     * @param username The username to lookup
     * @return Optional containing groupId if found, empty if not in cache or user has no group
     */
    public Optional<String> getGroupId(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        String groupId = usernameToGroupIdCache.getIfPresent(username);

        if (log.isDebugEnabled()) {
            log.debugf("GroupId cache lookup for username: %s, result: %s",
                    username, groupId != null ? groupId : "CACHE_MISS");
        }

        // Return empty if NO_GROUP marker or null
        if (groupId == null || NO_GROUP.equals(groupId)) {
            return Optional.empty();
        }

        return Optional.of(groupId);
    }

    /**
     * Store username → groupId mapping.
     *
     * @param username The username
     * @param groupId The groupId (can be null if user has no group)
     */
    public void put(String username, String groupId) {
        if (username == null || username.isBlank()) {
            return;
        }

        // Store NO_GROUP marker for users without groups to avoid repeated cache misses
        String valueToStore = (groupId == null || groupId.isBlank() || "1".equals(groupId))
                ? NO_GROUP
                : groupId;

        usernameToGroupIdCache.put(username, valueToStore);

        if (log.isDebugEnabled()) {
            log.debugf("Cached username→groupId mapping: %s → %s", username, valueToStore);
        }
    }

    /**
     * Invalidate mapping for a specific username.
     * Use this when groupId changes for a user.
     *
     * @param username The username to invalidate
     */
    public void invalidate(String username) {
        if (username != null && !username.isBlank()) {
            usernameToGroupIdCache.invalidate(username);
            log.debugf("Invalidated cache entry for username: %s", username);
        }
    }

    /**
     * Clear all cached mappings.
     * Use sparingly - only when necessary (e.g., major data migration).
     */
    public void invalidateAll() {
        usernameToGroupIdCache.invalidateAll();
        log.info("Cleared all username→groupId cache entries");
    }

    /**
     * Get cache statistics for monitoring.
     *
     * @return String representation of cache stats
     */
    public String getStats() {
        return usernameToGroupIdCache.stats().toString();
    }

    /**
     * Get current cache size.
     *
     * @return Number of entries in cache
     */
    public long size() {
        return usernameToGroupIdCache.estimatedSize();
    }

    /**
     * Check if cache has mapping for username.
     *
     * @param username The username to check
     * @return true if mapping exists in cache (even if NO_GROUP)
     */
    public boolean hasMapping(String username) {
        return username != null && usernameToGroupIdCache.getIfPresent(username) != null;
    }
}
