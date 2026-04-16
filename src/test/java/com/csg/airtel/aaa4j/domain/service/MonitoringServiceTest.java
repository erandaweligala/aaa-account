package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitoringServiceTest {

    private MeterRegistry registry;
    private ReactiveRedisDataSource redisDataSource;
    private ReactiveValueCommands<String, Long> redisValueCommands;
    private MonitoringService monitoringService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        redisDataSource = mock(ReactiveRedisDataSource.class);
        redisValueCommands = mock(ReactiveValueCommands.class);
        when(redisDataSource.value(String.class, Long.class)).thenReturn(redisValueCommands);
        when(redisValueCommands.incr(anyString())).thenReturn(Uni.createFrom().item(1L));
        when(redisValueCommands.incrby(anyString(), anyLong())).thenReturn(Uni.createFrom().item(1L));
        when(redisValueCommands.set(anyString(), anyLong())).thenReturn(Uni.createFrom().nullItem());
        monitoringService = new MonitoringService(registry, redisDataSource);
    }

    // ---- recordSessionCreated ----

    @Test
    void testRecordSessionCreated() {
        monitoringService.recordSessionCreated();
        assertEquals(1.0, monitoringService.getSessionsCreatedCount());
    }

    @Test
    void testRecordSessionCreated_incrementsRedis() {
        monitoringService.recordSessionCreated();
        verify(redisValueCommands).incr("dailyOpenSessionCount");
    }

    // ---- recordSessionTerminated ----

    @Test
    void testRecordSessionTerminated() {
        monitoringService.recordSessionTerminated();
        assertEquals(1.0, monitoringService.getSessionsTerminatedCount());
    }

    @Test
    void testRecordIdleSessionsTerminated() {
        monitoringService.recordIdleSessionsTerminated(5);
        monitoringService.recordIdleSessionsTerminated(0); // should be ignored
        assertEquals(5.0, monitoringService.getSessionsTerminatedCount());
    }

    @Test
    void testRecordIdleSessionsTerminated_usesIncrby() {
        monitoringService.recordIdleSessionsTerminated(3);
        verify(redisValueCommands).incrby("dailySessionTerminatedCount", 3L);
    }

    // ---- recordCOARequest (disconnect success) ----

    @Test
    void testRecordCOARequest() {
        monitoringService.recordCOARequest();
        assertEquals(1.0, monitoringService.getCOARequestsCount());
        verify(redisValueCommands).incr("coaRequestCount");
    }

    @Test
    void testRecordCOARequest_WithRedisException() {
        when(redisValueCommands.incr("coaRequestCount")).thenThrow(new RuntimeException("Redis failure"));
        monitoringService.recordCOARequest();
        // metric still incremented before Redis failure
        assertEquals(1.0, monitoringService.getCOARequestsCount());
    }

    // ---- recordDisconnectRequestFailure ----

    @Test
    void testRecordDisconnectRequestFailure() {
        monitoringService.recordDisconnectRequestFailure();
        assertEquals(1.0, monitoringService.getDisconnectFailureCount());
        verify(redisValueCommands).incr("dailyDisconnectFailureCount");
    }

    @Test
    void testRecordDisconnectRequestFailure_multipleIncrements() {
        monitoringService.recordDisconnectRequestFailure();
        monitoringService.recordDisconnectRequestFailure();
        monitoringService.recordDisconnectRequestFailure();
        assertEquals(3.0, monitoringService.getDisconnectFailureCount());
    }

    // ---- getDailyCoaRequestCount (success count) ----

    @Test
    void testGetDailyCoaRequestCount_FromRedis() {
        when(redisValueCommands.get("coaRequestCount")).thenReturn(Uni.createFrom().item(50L));
        monitoringService.getDailyCoaRequestCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(50L);
    }

    @Test
    void testGetDailyCoaRequestCount_FallbackToInMemory() {
        when(redisValueCommands.get("coaRequestCount")).thenReturn(Uni.createFrom().nullItem());
        monitoringService.getDailyCoaRequestCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(0L);
    }

    @Test
    void testGetDailyCoaRequestCount_OnRedisFailure() {
        when(redisValueCommands.get("coaRequestCount"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis down")));
        monitoringService.getDailyCoaRequestCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(0L);
    }

    // ---- getDailySessionCreatedCount ----

    @Test
    void testGetDailySessionCreatedCount_FromRedis() {
        when(redisValueCommands.get("dailyOpenSessionCount")).thenReturn(Uni.createFrom().item(10L));
        monitoringService.getDailySessionCreatedCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(10L);
    }

    @Test
    void testGetDailySessionCreatedCount_FallbackToInMemory() {
        when(redisValueCommands.get("dailyOpenSessionCount")).thenReturn(Uni.createFrom().nullItem());
        monitoringService.recordSessionCreated();
        // in-memory should be 1 but Redis returns null -> fallback
        monitoringService.getDailySessionCreatedCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(1L);
    }

    // ---- getDailySessionTerminatedCount ----

    @Test
    void testGetDailySessionTerminatedCount_FromRedis() {
        when(redisValueCommands.get("dailySessionTerminatedCount")).thenReturn(Uni.createFrom().item(7L));
        monitoringService.getDailySessionTerminatedCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(7L);
    }

    @Test
    void testGetDailySessionTerminatedCount_OnRedisFailure() {
        when(redisValueCommands.get("dailySessionTerminatedCount"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis down")));
        monitoringService.getDailySessionTerminatedCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(0L);
    }

    // ---- getDailyDisconnectFailureCount ----

    @Test
    void testGetDailyDisconnectFailureCount_FromRedis() {
        when(redisValueCommands.get("dailyDisconnectFailureCount")).thenReturn(Uni.createFrom().item(3L));
        monitoringService.getDailyDisconnectFailureCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(3L);
    }

    @Test
    void testGetDailyDisconnectFailureCount_OnRedisFailure() {
        when(redisValueCommands.get("dailyDisconnectFailureCount"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis down")));
        monitoringService.recordDisconnectRequestFailure();
        // fallback: in-memory = 1
        monitoringService.getDailyDisconnectFailureCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(1L);
    }

    // ---- resetDailyCounters (midnight scheduler) ----

    @Test
    void testResetDailyCounters_resetsAllInMemoryCounts() {
        monitoringService.recordSessionCreated();
        monitoringService.recordSessionTerminated();
        monitoringService.recordCOARequest();
        monitoringService.recordDisconnectRequestFailure();

        monitoringService.resetDailyCounters();

        // Lifetime counters must NOT be affected
        assertEquals(1.0, monitoringService.getSessionsCreatedCount());
        assertEquals(1.0, monitoringService.getSessionsTerminatedCount());
        assertEquals(1.0, monitoringService.getCOARequestsCount());
        assertEquals(1.0, monitoringService.getDisconnectFailureCount());

        // After reset, in-memory daily counts should be 0 (Redis returns null -> fallback 0)
        when(redisValueCommands.get(anyString())).thenReturn(Uni.createFrom().nullItem());

        monitoringService.getDailySessionCreatedCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(0L);

        monitoringService.getDailySessionTerminatedCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(0L);

        monitoringService.getDailyCoaRequestCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(0L);

        monitoringService.getDailyDisconnectFailureCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertItem(0L);
    }

    @Test
    void testResetDailyCounters_resetsAllRedisKeys() {
        monitoringService.resetDailyCounters();
        verify(redisValueCommands).set("dailyOpenSessionCount", 0L);
        verify(redisValueCommands).set("dailySessionTerminatedCount", 0L);
        verify(redisValueCommands).set("coaRequestCount", 0L);
        verify(redisValueCommands).set("dailyDisconnectFailureCount", 0L);
    }
}
