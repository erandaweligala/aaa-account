package com.csg.airtel.aaa4j.application.listener;

import com.csg.airtel.aaa4j.domain.model.CacheSyncEvent;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Consumes cache synchronization events replicated from the peer site (DC↔DR) by
 * Kafka MirrorMaker 2 and applies each event directly to the local Redis cache.
 *
 * <p>Applying the update goes through {@link CacheClient#applySync}, which writes to
 * Redis without re-publishing a sync event, preventing an infinite replication loop.</p>
 *
 * <p>Topic name convention (MirrorMaker 2):
 * <ul>
 *   <li>DC site publishes to {@code cache-sync-dc}; MM2 replicates it to the DR cluster
 *       as {@code dc.cache-sync-dc}.</li>
 *   <li>DR site publishes to {@code cache-sync-dr}; MM2 replicates it to the DC cluster
 *       as {@code dr.cache-sync-dr}.</li>
 * </ul>
 * Configure {@code CACHE_SYNC_INCOMING_TOPIC} per site so this consumer reads the
 * correct mirrored topic.</p>
 */
@ApplicationScoped
public class CacheSyncConsumer {

    private static final Logger log = Logger.getLogger(CacheSyncConsumer.class);

    private final CacheClient cacheClient;

    @Inject
    public CacheSyncConsumer(CacheClient cacheClient) {
        this.cacheClient = cacheClient;
    }

    /**
     * Receives a cache sync event from the peer site and applies it to local Redis.
     * Failures are logged and swallowed — processing continues to avoid blocking the consumer.
     */
    @Incoming("cache-sync-incoming")
    @Acknowledgment(Acknowledgment.Strategy.PRE_PROCESSING)
    public Uni<Void> onCacheSyncEvent(CacheSyncEvent event) {
        if (event == null || event.key() == null) {
            return Uni.createFrom().voidItem();
        }

        log.debugf("Received cache sync event: op=%s key=%s", event.operation(), event.key());

        return cacheClient.applySync(event.key(), event.value(), event.operation())
                .onFailure().invoke(e ->
                        log.warnf("Failed to apply cache sync [op=%s, key=%s]: %s",
                                event.operation(), event.key(), e.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}
