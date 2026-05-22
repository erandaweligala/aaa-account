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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;

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

    @Inject
    public SessionCacheCodec(ObjectMapper jsonMapper) {
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
    }

    public byte[] encode(UserSessionData data) throws IOException {
        return cborWriter.writeValueAsBytes(data);
    }

    public UserSessionData decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            return null;
        }
        if (payload[0] == JSON_OBJECT_START) {
            return jsonReader.readValue(payload);
        }
        return cborReader.readValue(payload);
    }
}
