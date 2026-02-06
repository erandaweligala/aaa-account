package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MonitoringServiceTest {

    private MeterRegistry registry;
    private ReactiveRedisDataSource redisDataSource;
    private ReactiveValueCommands<String, Long> redisValueCommands;
    private MonitoringService monitoringService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
// Use the real SimpleMeterRegistry designed for testing
        registry = new SimpleMeterRegistry();

        redisDataSource = mock(ReactiveRedisDataSource.class);
        redisValueCommands = mock(ReactiveValueCommands.class);

        // Mock Redis command initialization
        when(redisDataSource.value(String.class, Long.class)).thenReturn(redisValueCommands);

        monitoringService = new MonitoringService(registry, redisDataSource);
    }


    @Test
    void testGetDailyCoaRequestCount_OnRedisFailure() {
        // Redis throws exception, recover with in-memory value
        when(redisValueCommands.get("coaRequestCount"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis Error")));

        monitoringService.getDailyCoaRequestCount()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(0L);
    }


    @Test
    void testRecordCOARequest_WithException() {
        // Simulate an unexpected error to cover the catch block
        // We can't easily make the counter throw, but we can make Redis throw a synchronous error
        when(redisValueCommands.incr(anyString())).thenThrow(new RuntimeException("Immediate Fail"));

        // Should not throw exception to caller (caught internally)
        monitoringService.recordCOARequest();

        // Metric should still have incremented before the Redis failure
        assertEquals(1.0, monitoringService.getCOARequestsCount());
    }

    @Test
    void testRecordSessionCreated() {
        monitoringService.recordSessionCreated();
        // Since we can't easily access the private counter fields,
        // we check that the method completes without exception.
        assertEquals(1.0, monitoringService.getSessionsCreatedCount());
    }

    @Test
    void testRecordSessionTerminated() {
        monitoringService.recordSessionTerminated();
        assertEquals(1.0, monitoringService.getSessionsTerminatedCount());
    }

    @Test
    void testRecordIdleSessionsTerminated() {
        monitoringService.recordIdleSessionsTerminated(5);
        monitoringService.recordIdleSessionsTerminated(0); // Should ignore 0
        assertEquals(5.0, monitoringService.getSessionsTerminatedCount());
    }

    @Test
    void testRecordCOARequest() {
        // Use Uni.createFrom().item() for valid long returns
        when(redisValueCommands.incr(anyString())).thenReturn(Uni.createFrom().item(1L));

        monitoringService.recordCOARequest();

        assertEquals(1.0, monitoringService.getCOARequestsCount());
        verify(redisValueCommands).incr(anyString());
    }

    @Test
    void testGetDailyCoaRequestCount_FromRedis() {
        when(redisValueCommands.get("coaRequestCount")).thenReturn(Uni.createFrom().item(50L));

        Uni<Long> result = monitoringService.getDailyCoaRequestCount();

        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(50L);
    }

    @Test
    void testGetDailyCoaRequestCount_FallbackToInMemory() {
        // Mock Redis returning null
        when(redisValueCommands.get("coaRequestCount")).thenReturn(Uni.createFrom().nullItem());

        Uni<Long> result = monitoringService.getDailyCoaRequestCount();

        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(0L); // Initial in-memory value
    }

    @Test
    void testGetDailyCoaRequestCount_OnFailure() {
        // Mock Redis failure
        when(redisValueCommands.get("coaRequestCount"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis Down")));

        Uni<Long> result = monitoringService.getDailyCoaRequestCount();

        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(0L); // Falls back to in-memory
    }

    @Test
    void testNewDayReset() throws Exception {
        // 1. Setup Redis mocks using .nullItem() for the 'set' operation
        when(redisValueCommands.set(anyString(), anyLong())).thenReturn(Uni.createFrom().nullItem());
        when(redisValueCommands.incr(anyString())).thenReturn(Uni.createFrom().item(1L));

        // 2. Use reflection to simulate that 'currentDay' was yesterday
        Field dayField = MonitoringService.class.getDeclaredField("currentDay");
        dayField.setAccessible(true);
        dayField.set(monitoringService, LocalDate.now().minusDays(1));

        // 3. Trigger the logic
        monitoringService.recordCOARequest();

        // 4. Verify Redis reset to 0 was called
        verify(redisValueCommands).set(eq("coaRequestCount"), eq(0L));

        // 5. Verify the internal state reset
        assertEquals(LocalDate.now(), dayField.get(monitoringService));
    }
}