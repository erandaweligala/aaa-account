package com.csg.airtel.aaa4j.domain.produce;

import com.csg.airtel.aaa4j.domain.model.CacheSyncEvent;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

/**
 * Publishes cache synchronization events to Kafka so the peer site (DC↔DR) can keep
 * its Redis cache in sync.
 *
 * <p>Publishing is <em>fire-and-forget</em>: a failure here is logged as a warning but
 * never propagates to the caller. Cache sync is best-effort — on rare loss the peer
 * site's cache entry will become eventually consistent once the next accounting event
 * for that user arrives.</p>
 *
 * <p>The Kafka topic is replicated to the peer cluster by Kafka MirrorMaker 2.
 * The peer's {@link com.csg.airtel.aaa4j.application.listener.CacheSyncConsumer}
 * consumes the mirrored topic and applies each event directly to its local Redis,
 * <em>without</em> re-publishing, which prevents an infinite sync loop.</p>
 */
@ApplicationScoped
public class CacheSyncProducer {

    private static final Logger log = Logger.getLogger(CacheSyncProducer.class);

    private final Emitter<CacheSyncEvent> emitter;

    public CacheSyncProducer(@Channel("cache-sync-events") Emitter<CacheSyncEvent> emitter) {
        this.emitter = emitter;
    }

    /**
     * Publishes a cache sync event for an UPSERT (set) operation.
     *
     * @param key   full Redis key (e.g. {@code "user:john"})
     * @param value serialized Redis value
     */
    public void publishUpsert(String key, String value) {
        publish(key, value, CacheSyncEvent.UPSERT);
    }

    /**
     * Publishes a cache sync event for a DELETE operation.
     *
     * @param key full Redis key (e.g. {@code "user:john"})
     */
    public void publishDelete(String key) {
        publish(key, null, CacheSyncEvent.DELETE);
    }

    private void publish(String key, String value, String operation) {
        try {
            emitter.send(new CacheSyncEvent(key, value, operation, System.currentTimeMillis()));
        } catch (Exception e) {
            log.warnf("Failed to publish cache sync event [op=%s, key=%s]: %s", operation, key, e.getMessage());
        }
    }
}
