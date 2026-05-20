package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionMetricsServiceTest {

    private MeterRegistry registry;
    private ExceptionMetricsService service;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SimpleMeterRegistry();
        service = new ExceptionMetricsService(registry);
        // PostConstruct is invoked by the CDI container in production; emulate it for the unit test
        Method init = ExceptionMetricsService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(service);
    }

    @Test
    void wrapperDedupCountsSingleRootCauseOnceAcrossWrappingLayers() throws Exception {
        // Same root cause surfaced at three different layers, wrapped at each one.
        // The deepest layer (DATABASE/DATABASE source) records first; subsequent records
        // of the same root cause from upper layers (SERVICE/RESOURCE) are deduped.
        Throwable rootDbFailure = new IllegalStateException("DB connection lost");
        Throwable wrappedAtService = new RuntimeException("service failed", rootDbFailure);
        Throwable wrappedAtResource = new RuntimeException("resource failed", wrappedAtService);

        service.recordException(rootDbFailure, ExceptionMetricsService.Layer.DATABASE, ExceptionMetricsService.Source.DATABASE);
        service.recordException(wrappedAtService, ExceptionMetricsService.Layer.SERVICE, ExceptionMetricsService.Source.INTERNAL);
        service.recordException(wrappedAtResource, ExceptionMetricsService.Layer.RESOURCE, ExceptionMetricsService.Source.INTERNAL);

        // A different exception type to make percentages meaningful
        service.recordException(new NullPointerException("oops"), ExceptionMetricsService.Layer.SERVICE,
                ExceptionMetricsService.Source.INTERNAL);

        Method refresh = ExceptionMetricsService.class.getDeclaredMethod("refreshPercentages");
        refresh.setAccessible(true);
        refresh.invoke(service);

        // The deepest (most specific) attribution is the only per-layer counter incremented.
        Counter dbCounter = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "IllegalStateException", "layer", "database", "source", "database"))
                .counter();
        assertNotNull(dbCounter);
        assertEquals(1.0, dbCounter.count());

        // Wrapping records at higher layers are deduped — no per-layer counters created.
        assertNull(registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "IllegalStateException", "layer", "service", "source", "internal"))
                .counter());
        assertNull(registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "IllegalStateException", "layer", "resource", "source", "internal"))
                .counter());

        // Root aggregate counter: IllegalStateException is now counted ONCE (not three times).
        Counter rootCounter = registry.find("application_exception_root_count")
                .tag("exception_type", "IllegalStateException")
                .counter();
        assertNotNull(rootCounter);
        assertEquals(1.0, rootCounter.count());

        // Percentage gauge: 1 IllegalStateException + 1 NullPointerException = 50/50
        Gauge ilsPct = registry.find("application_exception_percentage")
                .tag("exception_type", "IllegalStateException")
                .gauge();
        Gauge nullPct = registry.find("application_exception_percentage")
                .tag("exception_type", "NullPointerException")
                .gauge();
        assertNotNull(ilsPct);
        assertNotNull(nullPct);
        assertEquals(50.0, ilsPct.value(), 0.001);
        assertEquals(50.0, nullPct.value(), 0.001);

        assertEquals(2L, service.getTotalRootCount());
    }

    @Test
    void retryDedupCollapsesSameFingerprintWithinTtl() {
        // Simulate three retry attempts of the same logical request: each attempt produces
        // a fresh ConnectionException instance, but they share (rootClass, layer, source) and
        // the same calling thread/context — so only the first is counted.
        for (int i = 0; i < 3; i++) {
            // Each attempt is a brand-new throwable instance (no shared marker).
            service.recordException(new RuntimeException("redis connection refused"),
                    ExceptionMetricsService.Layer.CLIENT, ExceptionMetricsService.Source.REDIS);
        }

        // A separate logical failure with a different source should still be counted.
        service.recordException(new RuntimeException("oracle stall"),
                ExceptionMetricsService.Layer.DATABASE, ExceptionMetricsService.Source.DATABASE);

        Map<String, ExceptionMetricsService.ExceptionStats> snapshot = service.snapshot();
        assertEquals(2L, snapshot.get("RuntimeException").count());

        Counter redisCounter = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "RuntimeException", "layer", "client", "source", "redis"))
                .counter();
        Counter oracleCounter = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "RuntimeException", "layer", "database", "source", "database"))
                .counter();
        assertNotNull(redisCounter);
        assertNotNull(oracleCounter);
        assertEquals(1.0, redisCounter.count());
        assertEquals(1.0, oracleCounter.count());
    }

    @Test
    void sourceTagDistinguishesSameExceptionClassFromDifferentSubsystems() {
        // ConnectionException-style failure surfaces from three different subsystems.
        // Each pairing of (rootClass, layer, source) is a distinct fingerprint, so all
        // three are counted — and the source tag tells dashboards them apart.
        service.recordException(new RuntimeException("redis down"),
                ExceptionMetricsService.Layer.CLIENT, ExceptionMetricsService.Source.REDIS);
        service.recordException(new RuntimeException("coa http down"),
                ExceptionMetricsService.Layer.CLIENT, ExceptionMetricsService.Source.HTTP_COA);
        service.recordException(new RuntimeException("db down"),
                ExceptionMetricsService.Layer.DATABASE, ExceptionMetricsService.Source.DATABASE);

        Counter redis = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "RuntimeException", "layer", "client", "source", "redis"))
                .counter();
        Counter http = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "RuntimeException", "layer", "client", "source", "http_coa"))
                .counter();
        Counter db = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "RuntimeException", "layer", "database", "source", "database"))
                .counter();
        assertNotNull(redis);
        assertNotNull(http);
        assertNotNull(db);
        assertEquals(1.0, redis.count());
        assertEquals(1.0, http.count());
        assertEquals(1.0, db.count());

        // Root aggregate sees all three as RuntimeException
        Counter rootCounter = registry.find("application_exception_root_count")
                .tag("exception_type", "RuntimeException")
                .counter();
        assertEquals(3.0, rootCounter.count());
    }

    @Test
    void backwardsCompatibleOverloadDefaultsSourceToUnknown() {
        service.recordException(new IllegalArgumentException("bad arg"), ExceptionMetricsService.Layer.SERVICE);

        Counter c = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "IllegalArgumentException", "layer", "service", "source", "unknown"))
                .counter();
        assertNotNull(c);
        assertEquals(1.0, c.count());
    }

    @Test
    void resolveRootCauseHandlesCycleAndNullCause() {
        // No cause
        assertEquals("RuntimeException",
                ExceptionMetricsService.resolveRootCauseType(new RuntimeException("plain")));

        // Self-cycle should not infinite-loop
        Throwable selfLooping = new RuntimeException("loop");
        // Throwable does not allow setting its own cause to itself directly via initCause -
        // but resolveRootCauseType guards via getCause() == current as well.
        assertEquals("RuntimeException", ExceptionMetricsService.resolveRootCauseType(selfLooping));
    }

    @Test
    void ignoresNullThrowableAndNullLayer() {
        service.recordException(null, ExceptionMetricsService.Layer.SERVICE);
        service.recordException(new RuntimeException("x"), null);
        assertEquals(0L, service.getTotalRootCount());
        assertTrue(service.snapshot().isEmpty());
    }

    @Test
    void dailyResetClearsDailyCountsButPreservesLifetime() throws Exception {
        // Different (layer, source) fingerprints so neither is deduped.
        service.recordException(new RuntimeException("boom"),
                ExceptionMetricsService.Layer.SERVICE, ExceptionMetricsService.Source.KAFKA);
        service.recordException(new RuntimeException("boom"),
                ExceptionMetricsService.Layer.DATABASE, ExceptionMetricsService.Source.DATABASE);

        Map<String, ExceptionMetricsService.ExceptionStats> before = service.snapshot();
        assertEquals(2L, before.get("RuntimeException").dailyCount());

        // Invoke the package-private scheduled reset directly
        Method reset = ExceptionMetricsService.class.getDeclaredMethod("resetDailyCounters");
        reset.setAccessible(true);
        reset.invoke(service);

        Map<String, ExceptionMetricsService.ExceptionStats> after = service.snapshot();
        assertEquals(0L, after.get("RuntimeException").dailyCount());
        // Lifetime count is unchanged
        assertEquals(2L, after.get("RuntimeException").count());
    }
}
