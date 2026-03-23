package com.csg.airtel.aaa4j.domain.model;

/**
 * Represents a cache synchronization event published to Kafka so that the peer site
 * (DC or DR) can replicate the same Redis write to its own local cache.
 *
 * <p>Flow:
 * <pre>
 *   Site A writes Redis  →  publishes CacheSyncEvent to Kafka  →  MirrorMaker replicates topic
 *   →  Site B consumes event  →  applies raw key/value directly to its local Redis
 * </pre>
 *
 * @param key       Full Redis key, e.g. {@code "user:john"} or {@code "group:john"}
 * @param value     Serialized Redis value (JSON string or comma-separated); {@code null} for DELETE
 * @param operation {@code "UPSERT"} to set the key or {@code "DELETE"} to remove it
 * @param timestamp Epoch millis when the event was created (used for ordering)
 */
public record CacheSyncEvent(
        String key,
        String value,
        String operation,
        long timestamp
) {
    public static final String UPSERT = "UPSERT";
    public static final String DELETE = "DELETE";
}
