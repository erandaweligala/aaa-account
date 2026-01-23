package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.clients.UserGroupMappingCache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Optimized service for user and group data lookups.
 * Demonstrates best practices for using the in-memory username→groupId mapping cache.
 *
 * This service provides high-performance lookup methods that minimize Redis calls
 * by leveraging the in-memory cache for common patterns.
 */
@ApplicationScoped
public class UserGroupLookupService {

    private static final Logger log = Logger.getLogger(UserGroupLookupService.class);

    private final CacheClient cacheClient;
    private final UserGroupMappingCache userGroupMappingCache;

    @Inject
    public UserGroupLookupService(CacheClient cacheClient, UserGroupMappingCache userGroupMappingCache) {
        this.cacheClient = cacheClient;
        this.userGroupMappingCache = userGroupMappingCache;
    }

    /**
     * Check if a user belongs to a group (fastest method).
     * Uses in-memory cache only - no Redis call if cached.
     *
     * @param username The username to check
     * @return true if user has a group, false otherwise or if not in cache
     */
    public boolean hasGroupCached(String username) {
        Optional<String> groupId = userGroupMappingCache.getGroupId(username);
        return groupId.isPresent();
    }

    /**
     * Get groupId for username with Redis fallback.
     * Recommended for scenarios where you need groupId but don't need full user data.
     *
     * @param username The username to lookup
     * @return Uni with Optional containing groupId if found
     */
    public Uni<Optional<String>> getGroupId(String username) {
        return cacheClient.getGroupIdForUsername(username);
    }

    /**
     * Get combined balances for a user (includes group balance if applicable).
     * Optimized version that uses mapping cache to minimize Redis calls.
     *
     * Use this method when you need balances but don't need full session data.
     *
     * @param username The username
     * @return Uni with combined list of user and group balances
     */
    public Uni<List<Balance>> getCombinedBalances(String username) {
        // First get user data (always needed for balances)
        return cacheClient.getUserData(username)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        return Uni.createFrom().item(List.of());
                    }

                    String groupId = userData.getGroupId();
                    boolean hasGroup = groupId != null && !groupId.isBlank() && !"1".equals(groupId);

                    if (!hasGroup) {
                        // No group - return user balances only
                        return Uni.createFrom().item(userData.getBalance() != null
                                ? userData.getBalance()
                                : List.of());
                    }

                    // Has group - fetch group balances and combine
                    return cacheClient.getUserData(groupId)
                            .onItem().transform(groupData -> {
                                List<Balance> combined = new java.util.ArrayList<>(
                                        userData.getBalance() != null ? userData.getBalance() : List.of());

                                if (groupData != null && groupData.getBalance() != null) {
                                    combined.addAll(groupData.getBalance());
                                }

                                return combined;
                            });
                });
    }

    /**
     * Check if user exists and has sufficient data to create a session.
     * Optimized to avoid fetching full data when possible.
     *
     * @param username The username to validate
     * @return Uni with validation result
     */
    public Uni<UserValidationResult> validateUserForSession(String username) {
        // Fast path: Check if we have cached group info
        Optional<String> cachedGroupId = userGroupMappingCache.getGroupId(username);

        if (cachedGroupId.isPresent()) {
            // User exists and has group (cached) - verify group data
            String groupId = cachedGroupId.get();
            return cacheClient.getUserData(groupId)
                    .onItem().transform(groupData -> {
                        if (groupData == null) {
                            log.warnf("Group data not found for groupId: %s (user: %s)", groupId, username);
                            return new UserValidationResult(false, "Group data not found", null);
                        }

                        return new UserValidationResult(true, "Valid group user", groupId);
                    });
        }

        // Slow path: Not in cache - fetch user data
        return cacheClient.getUserData(username)
                .onItem().transform(userData -> {
                    if (userData == null) {
                        return new UserValidationResult(false, "User not found", null);
                    }

                    String groupId = userData.getGroupId();
                    boolean hasGroup = groupId != null && !groupId.isBlank() && !"1".equals(groupId);

                    if (hasGroup) {
                        return new UserValidationResult(true, "Valid group user", groupId);
                    }

                    return new UserValidationResult(true, "Valid individual user", null);
                })
                .onItem().ifNull().continueWith(
                        new UserValidationResult(false, "User not found", null));
    }

    /**
     * Get full user and group data efficiently.
     * Uses the standard pattern which auto-populates the mapping cache.
     *
     * @param username The username
     * @return Uni with UserWithGroupData containing both user and group data
     */
    public Uni<UserWithGroupData> getUserWithGroupData(String username) {
        return cacheClient.getUserData(username)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        return Uni.createFrom().item(new UserWithGroupData(null, null));
                    }

                    String groupId = userData.getGroupId();
                    boolean hasGroup = groupId != null && !groupId.isBlank() && !"1".equals(groupId);

                    if (!hasGroup) {
                        return Uni.createFrom().item(new UserWithGroupData(userData, null));
                    }

                    // Fetch group data
                    return cacheClient.getUserData(groupId)
                            .onItem().transform(groupData ->
                                    new UserWithGroupData(userData, groupData));
                });
    }

    /**
     * Result of user validation check.
     */
    public record UserValidationResult(
            boolean isValid,
            String message,
            String groupId  // null if individual user or invalid
    ) {
        public boolean hasGroup() {
            return groupId != null;
        }

        public boolean isIndividualUser() {
            return isValid && groupId == null;
        }

        public boolean isGroupUser() {
            return isValid && groupId != null;
        }
    }

    /**
     * Combined user and group data.
     */
    public record UserWithGroupData(
            UserSessionData userData,
            UserSessionData groupData
    ) {
        public boolean hasUser() {
            return userData != null;
        }

        public boolean hasGroup() {
            return groupData != null;
        }

        public List<Balance> getCombinedBalances() {
            List<Balance> combined = new java.util.ArrayList<>();

            if (userData != null && userData.getBalance() != null) {
                combined.addAll(userData.getBalance());
            }

            if (groupData != null && groupData.getBalance() != null) {
                combined.addAll(groupData.getBalance());
            }

            return combined;
        }
    }
}
