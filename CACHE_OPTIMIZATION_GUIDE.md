# Cache Key Management with Partial Data - Solution Guide

## Problem Statement

When processing accounting requests with only a **username** (from `AccountingRequestDto`), the system needs to:
1. Check if the user belongs to a group (has a `groupId`)
2. Retrieve group balance data if applicable
3. Avoid creating unnecessary new sessions when data is incomplete

Previously, this required:
- **2 Redis lookups**: First fetch full `UserSessionData` to extract `groupId`, then fetch group data
- **High latency** at high TPS (2000+ requests/second)
- **Redundant data transfer**: Fetching full user object just to get groupId

## Solution: In-Memory Username→GroupId Mapping Cache

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Accounting Request                        │
│                  (username only available)                   │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              UserGroupMappingCache (In-Memory)               │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Cache: username → groupId                           │   │
│  │  - 100K entries capacity                             │   │
│  │  - 30 min TTL                                        │   │
│  │  - O(1) lookup (microseconds)                        │   │
│  └──────────────────────────────────────────────────────┘   │
└───────────────────────────┬─────────────────────────────────┘
                            │
                    ┌───────┴────────┐
                    │                │
              Cache Hit        Cache Miss
                    │                │
                    ▼                ▼
            Return groupId   Fetch from Redis
                            (auto-populate cache)
```

### Components

#### 1. UserGroupMappingCache
- **Location**: `/src/main/java/com/csg/airtel/aaa4j/external/clients/UserGroupMappingCache.java`
- **Technology**: Caffeine cache (high-performance in-memory cache)
- **Capacity**: 100,000 entries (adjust based on your user base)
- **TTL**: 30 minutes
- **Thread-safe**: Yes, safe for concurrent access

**Key Methods**:
```java
// Get groupId for username (returns Optional)
Optional<String> getGroupId(String username)

// Store mapping (called automatically by CacheClient)
void put(String username, String groupId)

// Invalidate specific entry (use when groupId changes)
void invalidate(String username)

// Get cache statistics
String getStats()
```

#### 2. Enhanced CacheClient
- **Auto-population**: Mapping cache is automatically populated when:
  - `getUserData()` is called
  - `storeUserData()` is called
  - `updateUserAndRelatedCaches()` is called

**New Method**:
```java
/**
 * Efficiently get groupId for username with in-memory cache
 * @param username The username to lookup
 * @return Uni with Optional<String> containing groupId if found
 */
Uni<Optional<String>> getGroupIdForUsername(String username)
```

## Usage Patterns

### Pattern 1: Check Group Membership (Fastest)

When you only need to know IF a user has a group:

```java
// Fast path: Check in-memory cache (microseconds)
Optional<String> groupId = userGroupMappingCache.getGroupId(username);

if (groupId.isPresent()) {
    // User is in group: groupId.get()
    log.infof("User %s belongs to group %s", username, groupId.get());
} else {
    // User has no group
    log.infof("User %s has no group", username);
}
```

### Pattern 2: Get GroupId with Redis Fallback (Recommended)

When you need the groupId but might not have it in cache yet:

```java
cacheClient.getGroupIdForUsername(request.username())
    .onItem().transformToUni(optionalGroupId -> {
        if (optionalGroupId.isPresent()) {
            String groupId = optionalGroupId.get();
            // Fetch group data
            return cacheClient.getUserData(groupId)
                .onItem().transform(groupData -> {
                    // Process group data
                    return processGroupData(groupData);
                });
        } else {
            // No group - proceed with individual user logic
            return processIndividualUser();
        }
    });
```

### Pattern 3: Full User Data (Current Pattern - Still Optimal)

When you need full user data anyway:

```java
// This automatically populates the mapping cache
cacheClient.getUserData(request.username())
    .onItem().transformToUni(userData -> {
        if (userData == null) {
            return handleNewUser(request);
        }

        String groupId = userData.getGroupId();
        if (groupId != null && !groupId.equals("1")) {
            // User is in group - fetch group balances
            return cacheClient.getUserData(groupId)
                .onItem().transform(UserSessionData::getBalance);
        }

        // Use individual user balances
        return Uni.createFrom().item(userData.getBalance());
    });
```

## Performance Comparison

### Before Optimization
```
Request with username only:
1. Redis GET user:username (2-5ms)
2. Deserialize JSON (0.5-1ms)
3. Extract groupId from object
4. Redis GET user:groupId (2-5ms)
5. Deserialize JSON (0.5-1ms)
──────────────────────────────
Total: 5-12ms per request
```

### After Optimization
```
Request with username (cache hit):
1. In-memory cache lookup (0.001-0.01ms)
2. Redis GET user:groupId (2-5ms)
3. Deserialize JSON (0.5-1ms)
──────────────────────────────
Total: 2.5-6ms per request
──────────────────────────────
Improvement: 50-60% faster
```

### At 2000 TPS
```
Before: 2000 requests × 10ms avg = 20,000ms = 20 seconds of Redis load
After:  2000 requests × 4ms avg = 8,000ms = 8 seconds of Redis load

Redis load reduction: 60%
```

## Avoiding Unnecessary Session Creation

### Scenario: Partial Data Validation

```java
public Uni<Void> validateUserBeforeSession(String username) {
    // Quick check: Does user exist and have group?
    return cacheClient.getGroupIdForUsername(username)
        .onItem().transformToUni(optionalGroupId -> {
            if (optionalGroupId.isEmpty()) {
                // User either doesn't exist or has no group
                // Fetch full data to determine which
                return cacheClient.getUserData(username)
                    .onItem().transformToUni(userData -> {
                        if (userData == null) {
                            log.warnf("User %s not found - cannot create session", username);
                            return Uni.createFrom().failure(
                                new ValidationException("User not found"));
                        }

                        // User exists but has no group - proceed with individual logic
                        return processIndividualUser(userData);
                    });
            }

            // User has group - check group balance before creating session
            String groupId = optionalGroupId.get();
            return cacheClient.getUserData(groupId)
                .onItem().transformToUni(groupData -> {
                    if (hasInsufficientBalance(groupData)) {
                        log.warnf("Group %s has insufficient balance - rejecting session", groupId);
                        return Uni.createFrom().failure(
                            new ValidationException("Insufficient group balance"));
                    }

                    // Proceed with session creation
                    return createSessionForGroupUser(username, groupId);
                });
        });
}
```

## Cache Maintenance

### Invalidation Strategy

The mapping cache should be invalidated when:

1. **User's groupId changes** (rare):
```java
// After updating user's group membership
userGroupMappingCache.invalidate(username);
```

2. **User is deleted** (rare):
```java
// After deleting user
userGroupMappingCache.invalidate(username);
cacheClient.deleteKey(username);
```

3. **Mass data migration** (very rare):
```java
// Clear all mappings (use sparingly!)
userGroupMappingCache.invalidateAll();
```

### Monitoring

Monitor cache effectiveness:

```java
@Scheduled(every = "5m")
public void logCacheStats() {
    String stats = userGroupMappingCache.getStats();
    long size = userGroupMappingCache.size();

    log.infof("UserGroupMappingCache stats - Size: %d, Stats: %s", size, stats);

    // Caffeine stats include:
    // - Hit rate
    // - Miss rate
    // - Eviction count
    // - Load success/failure count
}
```

## Configuration Tuning

Adjust cache parameters in `UserGroupMappingCache.java`:

```java
// For larger user bases
.maximumSize(500_000)  // Increase capacity

// For more frequently changing groups
.expireAfterWrite(Duration.ofMinutes(15))  // Reduce TTL

// For better observability
.recordStats()  // Already enabled
```

## Best Practices

1. **Always use the mapping cache for groupId lookups** instead of fetching full UserSessionData
2. **Don't manually populate the cache** - it's auto-populated by CacheClient
3. **Monitor cache hit rate** - should be > 90% in steady state
4. **Invalidate on group changes** - maintain cache consistency
5. **Use Pattern 2** (getGroupIdForUsername) for most new code
6. **Pattern 3 is still optimal** when you need full user data anyway

## Migration Guide

### Before
```java
// Old pattern: Always fetch full data
cacheClient.getUserData(username)
    .onItem().transformToUni(userData -> {
        if (userData != null && userData.getGroupId() != null) {
            String groupId = userData.getGroupId();
            // Process group...
        }
    });
```

### After
```java
// New pattern: Fast groupId check first
cacheClient.getGroupIdForUsername(username)
    .onItem().transformToUni(optionalGroupId -> {
        if (optionalGroupId.isPresent()) {
            String groupId = optionalGroupId.get();
            // Process group...
        } else {
            // No group - skip expensive data fetch if not needed
        }
    });
```

## Summary

✅ **Problem Solved**: Efficient groupId lookup with only username
✅ **Performance**: 50-60% improvement for group user lookups
✅ **Scalability**: Reduces Redis load by 60% at 2000 TPS
✅ **Simplicity**: Auto-populated cache, zero configuration needed
✅ **Reliability**: 30-minute TTL prevents stale data issues

The in-memory mapping cache provides the missing piece for efficient cache key management with partial data, enabling high-throughput processing while avoiding unnecessary session creation.
