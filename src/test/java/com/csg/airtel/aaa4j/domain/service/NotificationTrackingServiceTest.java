package com.csg.airtel.aaa4j.domain.service;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationTrackingServiceTest {

    private ReactiveRedisDataSource redisDataSource;
    private ReactiveValueCommands<String, String> valueCommands;
    private NotificationTrackingService service;

    // Test Data
    private final String USERNAME = "testUser";
    private final Long TEMPLATE_ID = 101L;
    private final String BUCKET_ID = "bucket500";
    private final Long THRESHOLD = 80L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redisDataSource = mock(ReactiveRedisDataSource.class);
        valueCommands = mock(ReactiveValueCommands.class);

        // Mock the chain: dataSource.value(...) -> valueCommands
        when(redisDataSource.value(eq(String.class), eq(String.class))).thenReturn(valueCommands);

        service = new NotificationTrackingService(redisDataSource);
    }

    @Test
    void testIsDuplicateNotification_True() {
        when(valueCommands.get(anyString())).thenReturn(Uni.createFrom().item("some-timestamp"));

        service.isDuplicateNotification(USERNAME, TEMPLATE_ID, BUCKET_ID, THRESHOLD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(true);

        verify(valueCommands).get(contains(USERNAME));
    }

    @Test
    void testIsDuplicateNotification_False() {
        when(valueCommands.get(anyString())).thenReturn(Uni.createFrom().nullItem());

        service.isDuplicateNotification(USERNAME, TEMPLATE_ID, BUCKET_ID, THRESHOLD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(false);
    }

    @Test
    void testIsDuplicateNotification_FailOpenOnError() {
        // Simulate Redis error
        when(valueCommands.get(anyString())).thenReturn(Uni.createFrom().failure(new RuntimeException("Redis down")));

        service.isDuplicateNotification(USERNAME, TEMPLATE_ID, BUCKET_ID, THRESHOLD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(false); // Should return false (fail-open) on error
    }

    @Test
    void testMarkNotificationSent_DefaultTtl() {
        when(valueCommands.setex(anyString(), anyLong(), anyString())).thenReturn(Uni.createFrom().nullItem());

        service.markNotificationSent(USERNAME, TEMPLATE_ID, BUCKET_ID, THRESHOLD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Verify default TTL of 3600 seconds (1 hour) is used
        verify(valueCommands).setex(anyString(), eq(3600L), anyString());
    }

    @Test
    void testMarkNotificationSent_CustomTtl() {
        when(valueCommands.setex(anyString(), anyLong(), anyString())).thenReturn(Uni.createFrom().nullItem());
        Duration customTtl = Duration.ofMinutes(5);

        service.markNotificationSent(USERNAME, TEMPLATE_ID, BUCKET_ID, THRESHOLD, customTtl)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(valueCommands).setex(anyString(), eq(300L), anyString());
    }

    @Test
    void testMarkNotificationSent_ErrorHandling() {
        when(valueCommands.setex(anyString(), anyLong(), anyString()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Write error")));

        service.markNotificationSent(USERNAME, TEMPLATE_ID, BUCKET_ID, THRESHOLD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted(); // Should recover and complete void
    }

    @Test
    void testClearNotificationTracking_Success() {
        when(valueCommands.getdel(anyString())).thenReturn(Uni.createFrom().item("deleted-val"));

        service.clearNotificationTracking(USERNAME, TEMPLATE_ID, BUCKET_ID, THRESHOLD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(valueCommands).getdel(anyString());
    }

    @Test
    void testClearNotificationTracking_Error() {
        // Setup failure
        when(valueCommands.getdel(anyString()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Delete failed")));

        service.clearNotificationTracking(USERNAME, TEMPLATE_ID, BUCKET_ID, THRESHOLD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure() // Change awaitItem() to awaitFailure()
                .assertFailedWith(RuntimeException.class, "Delete failed");
    }
}