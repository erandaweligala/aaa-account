package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Binary codec for UserSessionData backed by Jackson CBOR + Blackbird.
 *
 * Dual-read shim: payloads written before the CBOR rollout are still raw JSON.
 * JSON objects start with '{' (0x7B); CBOR top-level map headers fall in
 * 0xA0–0xBF — the two ranges are disjoint, so a single byte sniff routes to
 * the right reader with no exception-based fallback on the hot path.
 */
@ApplicationScoped
public class SessionCacheCodec {

    private static final byte JSON_OBJECT_START = (byte) '{';

    private final ObjectWriter cborWriter;
    private final ObjectReader cborReader;
    private final ObjectReader jsonReader;

    private final Timer encodeTimer;
    private final Timer decodeCborTimer;
    private final Timer decodeJsonTimer;
    private final DistributionSummary payloadBytes;

    @Inject
    public SessionCacheCodec(ObjectMapper jsonMapper, MeterRegistry registry) {
        CBORMapper cborMapper = CBORMapper.builder()
                .addModule(new BlackbirdModule())
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_EMPTY)
                .build();
        this.cborWriter = cborMapper.writerFor(UserSessionData.class);
        this.cborReader = cborMapper.readerFor(UserSessionData.class);
        this.jsonReader = jsonMapper.readerFor(UserSessionData.class);

        this.encodeTimer = Timer.builder("cache_codec_encode_seconds")
                .description("Time to serialize UserSessionData to CBOR bytes")
                .tag("format", "cbor")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.decodeCborTimer = Timer.builder("cache_codec_decode_seconds")
                .description("Time to deserialize UserSessionData from cache bytes")
                .tag("format", "cbor")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // Tracks the JSON-fallback rate during rollout; should drift to ~0 within one TTL window.
        this.decodeJsonTimer = Timer.builder("cache_codec_decode_seconds")
                .description("Time to deserialize UserSessionData from cache bytes")
                .tag("format", "json")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.payloadBytes = DistributionSummary.builder("cache_codec_payload_bytes")
                .description("On-wire size of UserSessionData payloads in Redis")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public byte[] encode(UserSessionData data) throws IOException {
        long startNanos = System.nanoTime();
        try {
            byte[] encoded = cborWriter.writeValueAsBytes(data);
            payloadBytes.record(encoded.length);
            return encoded;
        } finally {
            encodeTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }

    public UserSessionData decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            return null;
        }
        boolean isJson = payload[0] == JSON_OBJECT_START;
        long startNanos = System.nanoTime();
        try {
            return isJson ? jsonReader.readValue(payload) : cborReader.readValue(payload);
        } finally {
            long elapsed = System.nanoTime() - startNanos;
            (isJson ? decodeJsonTimer : decodeCborTimer).record(elapsed, TimeUnit.NANOSECONDS);
        }
    }
}
