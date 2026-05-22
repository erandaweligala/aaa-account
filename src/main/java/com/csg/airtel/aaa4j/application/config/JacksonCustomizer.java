package com.csg.airtel.aaa4j.application.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Replaces Jackson's reflection-based bean access with bytecode-generated
 * accessors. At 2000 TPS the cache path does ~3-4 full UserSessionData
 * ser/deser per message; Blackbird removes the reflection cost on every
 * getter/setter and is measurably the dominant Jackson hotspot.
 */
@Singleton
public class JacksonCustomizer implements ObjectMapperCustomizer {
    @Override
    public void customize(ObjectMapper mapper) {
        mapper.registerModule(new BlackbirdModule());
    }
}
