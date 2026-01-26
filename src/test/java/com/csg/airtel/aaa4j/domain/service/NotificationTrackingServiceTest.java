package com.csg.airtel.aaa4j.domain.service;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationTrackingServiceTest {

    @Mock
    private ReactiveRedisDataSource reactiveRedisDataSource;

    @Mock
    private ReactiveValueCommands<String, String> valueCommands;

    private NotificationTrackingService notificationTrackingService;

    private String testUsername;
    private Long testTemplateId;
    private String testBucketId;
    private Long testThresholdLevel;

    @BeforeEach
    void setUp() {
        when(reactiveRedisDataSource.value(String.class, String.class)).thenReturn(valueCommands);
        notificationTrackingService = new NotificationTrackingService(reactiveRedisDataSource);

        testUsername = "testuser";
        testTemplateId = 100L;
        testBucketId = "bucket-123";
        testThresholdLevel = 80L;
    }

    @Test
    void testIsDuplicateNotification_NotDuplicate() {
        when(valueCommands.get(anyString())).thenReturn(Uni.createFrom().nullItem());

        Boolean isDuplicate = notificationTrackingService.isDuplicateNotification(
            testUsername, testTemplateId, testBucketId, testThresholdLevel
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertFalse(isDuplicate);
        verify(valueCommands).get(anyString());
    }

    @Test
    void testIsDuplicateNotification_IsDuplicate() {
        when(valueCommands.get(anyString())).thenReturn(Uni.createFrom().item("timestamp"));

        Boolean isDuplicate = notificationTrackingService.isDuplicateNotification(
            testUsername, testTemplateId, testBucketId, testThresholdLevel
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertTrue(isDuplicate);
    }

    @Test
    void testIsDuplicateNotification_RedisError_ReturnsFalse() {
        when(valueCommands.get(anyString())).thenReturn(Uni.createFrom().failure(new RuntimeException("Redis error")));

        Boolean isDuplicate = notificationTrackingService.isDuplicateNotification(
            testUsername, testTemplateId, testBucketId, testThresholdLevel
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertFalse(isDuplicate); // Fail-open behavior
    }

    @Test
    void testMarkNotificationSent_DefaultTTL() {
        when(valueCommands.setex(anyString(), anyLong(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        notificationTrackingService.markNotificationSent(
            testUsername, testTemplateId, testBucketId, testThresholdLevel
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(valueCommands).setex(anyString(), eq(3600L), anyString()); // 1 hour default
    }

    @Test
    void testMarkNotificationSent_CustomTTL() {
        Duration customTTL = Duration.ofMinutes(30);
        when(valueCommands.setex(anyString(), anyLong(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        notificationTrackingService.markNotificationSent(
            testUsername, testTemplateId, testBucketId, testThresholdLevel, customTTL
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(valueCommands).setex(anyString(), eq(1800L), anyString());
    }

    @Test
    void testMarkNotificationSent_RedisError_DoesNotFail() {
        when(valueCommands.setex(anyString(), anyLong(), anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis error")));

        notificationTrackingService.markNotificationSent(
            testUsername, testTemplateId, testBucketId, testThresholdLevel
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(valueCommands).setex(anyString(), anyLong(), anyString());
    }

    @Test
    void testClearNotificationTracking() {
        when(valueCommands.getdel(anyString())).thenReturn(Uni.createFrom().item("deleted-value"));

        notificationTrackingService.clearNotificationTracking(
            testUsername, testTemplateId, testBucketId, testThresholdLevel
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(valueCommands).getdel(anyString());
    }

    @Test
    void testClearNotificationTracking_Error() {
        when(valueCommands.getdel(anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis error")));

        notificationTrackingService.clearNotificationTracking(
            testUsername, testTemplateId, testBucketId, testThresholdLevel
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(valueCommands).getdel(anyString());
    }
}
