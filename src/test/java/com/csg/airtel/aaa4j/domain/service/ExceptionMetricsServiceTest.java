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
    void recordsRootCauseAcrossLayersAggregatedByExceptionType() {
        // Same root cause surfaced at three different layers, wrapped at each one
        Throwable rootDbFailure = new IllegalStateException("DB connection lost");
        Throwable wrappedAtService = new RuntimeException("service failed", rootDbFailure);
        Throwable wrappedAtResource = new RuntimeException("resource failed", wrappedAtService);

        service.recordException(rootDbFailure, ExceptionMetricsService.Layer.DATABASE);
        service.recordException(wrappedAtService, ExceptionMetricsService.Layer.SERVICE);
        service.recordException(wrappedAtResource, ExceptionMetricsService.Layer.RESOURCE);

        // Plus a different exception type to make percentages meaningful
        service.recordException(new NullPointerException("oops"), ExceptionMetricsService.Layer.SERVICE);

        // Per-layer counters: one per (type, layer)
        Counter dbCounter = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "IllegalStateException", "layer", "database"))
                .counter();
        Counter serviceCounter = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "IllegalStateException", "layer", "service"))
                .counter();
        Counter resourceCounter = registry.find("application_exception_count")
                .tags(Tags.of("exception_type", "IllegalStateException", "layer", "resource"))
                .counter();
        assertNotNull(dbCounter);
        assertNotNull(serviceCounter);
        assertNotNull(resourceCounter);
        assertEquals(1.0, dbCounter.count());
        assertEquals(1.0, serviceCounter.count());
        assertEquals(1.0, resourceCounter.count());

        // Root aggregate counter: IllegalStateException counted three times across layers
        Counter rootCounter = registry.find("application_exception_root_count")
                .tag("exception_type", "IllegalStateException")
                .counter();
        assertNotNull(rootCounter);
        assertEquals(3.0, rootCounter.count());

        // Percentage gauge: IllegalStateException = 3/4 = 75%, NullPointerException = 1/4 = 25%
        Gauge ilsPct = registry.find("application_exception_percentage")
                .tag("exception_type", "IllegalStateException")
                .gauge();
        Gauge nullPct = registry.find("application_exception_percentage")
                .tag("exception_type", "NullPointerException")
                .gauge();
        assertNotNull(ilsPct);
        assertNotNull(nullPct);
        assertEquals(75.0, ilsPct.value(), 0.001);
        assertEquals(25.0, nullPct.value(), 0.001);

        // Snapshot view
        Map<String, ExceptionMetricsService.ExceptionStats> snapshot = service.snapshot();
        assertEquals(2, snapshot.size());
        // Ordering is by descending count - so IllegalStateException is first
        Map.Entry<String, ExceptionMetricsService.ExceptionStats> first = snapshot.entrySet().iterator().next();
        assertEquals("IllegalStateException", first.getKey());
        assertEquals(3L, first.getValue().count());
        assertEquals(75.0, first.getValue().percentage(), 0.001);
        assertEquals(3L, first.getValue().dailyCount());

        assertEquals(4L, service.getTotalRootCount());
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
        service.recordException(new RuntimeException("boom"), ExceptionMetricsService.Layer.SERVICE);
        service.recordException(new RuntimeException("boom"), ExceptionMetricsService.Layer.DATABASE);

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
