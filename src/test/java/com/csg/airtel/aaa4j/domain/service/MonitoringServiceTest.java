package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitoringServiceTest {

    private MeterRegistry meterRegistry;

    @Mock
    private ReactiveRedisDataSource reactiveRedisDataSource;

    @Mock
    private ReactiveValueCommands<String, Long> valueCommands;

    private MonitoringService monitoringService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(reactiveRedisDataSource.value(String.class, Long.class)).thenReturn(valueCommands);
        when(valueCommands.set(anyString(), anyLong())).thenReturn(Uni.createFrom().voidItem());
        when(valueCommands.incr(anyString())).thenReturn(Uni.createFrom().item(1L));

        monitoringService = new MonitoringService(meterRegistry, reactiveRedisDataSource);
    }

    @Test
    void testRecordSessionCreated() {
        double initialCount = monitoringService.getSessionsCreatedCount();

        monitoringService.recordSessionCreated();

        double newCount = monitoringService.getSessionsCreatedCount();
        assertEquals(initialCount + 1, newCount);
    }

    @Test
    void testRecordSessionTerminated() {
        double initialCount = monitoringService.getSessionsTerminatedCount();

        monitoringService.recordSessionTerminated();

        double newCount = monitoringService.getSessionsTerminatedCount();
        assertEquals(initialCount + 1, newCount);
    }

    @Test
    void testRecordIdleSessionsTerminated() {
        double initialCount = monitoringService.getSessionsTerminatedCount();
        int terminatedCount = 5;

        monitoringService.recordIdleSessionsTerminated(terminatedCount);

        double newCount = monitoringService.getSessionsTerminatedCount();
        assertEquals(initialCount + terminatedCount, newCount);
    }

    @Test
    void testRecordIdleSessionsTerminated_ZeroCount() {
        double initialCount = monitoringService.getSessionsTerminatedCount();

        monitoringService.recordIdleSessionsTerminated(0);

        double newCount = monitoringService.getSessionsTerminatedCount();
        assertEquals(initialCount, newCount);
    }

    @Test
    void testRecordCOARequest() {
        double initialCount = monitoringService.getCOARequestsCount();

        monitoringService.recordCOARequest();

        double newCount = monitoringService.getCOARequestsCount();
        assertEquals(initialCount + 1, newCount);
    }

    @Test
    void testGetDailyCoaRequestCount_Success() {
        when(valueCommands.get(anyString())).thenReturn(Uni.createFrom().item(10L));

        Long count = monitoringService.getDailyCoaRequestCount()
            .await().indefinitely();

        assertNotNull(count);
        assertTrue(count >= 0);
    }

    @Test
    void testGetDailyCoaRequestCount_NullFromRedis() {
        when(valueCommands.get(anyString())).thenReturn(Uni.createFrom().nullItem());

        Long count = monitoringService.getDailyCoaRequestCount()
            .await().indefinitely();

        assertNotNull(count);
        assertTrue(count >= 0);
    }

    @Test
    void testGetSessionsCreatedCount() {
        monitoringService.recordSessionCreated();
        monitoringService.recordSessionCreated();

        double count = monitoringService.getSessionsCreatedCount();

        assertEquals(2.0, count);
    }

    @Test
    void testGetSessionsTerminatedCount() {
        monitoringService.recordSessionTerminated();
        monitoringService.recordSessionTerminated();
        monitoringService.recordSessionTerminated();

        double count = monitoringService.getSessionsTerminatedCount();

        assertEquals(3.0, count);
    }

    @Test
    void testGetCOARequestsCount() {
        monitoringService.recordCOARequest();

        double count = monitoringService.getCOARequestsCount();

        assertEquals(1.0, count);
    }
}
