package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.QuotaNotificationEvent;
import com.csg.airtel.aaa4j.domain.model.ThresholdGlobalTemplates;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuotaNotificationServiceTest {

    private AccountProducer accountProducer;
    private MessageTemplateCacheService templateCacheService;
    private NotificationTrackingService notificationTrackingService;
    private QuotaNotificationService quotaNotificationService;

    @BeforeEach
    void setUp() {
        accountProducer = mock(AccountProducer.class);
        templateCacheService = mock(MessageTemplateCacheService.class);
        notificationTrackingService = mock(NotificationTrackingService.class);

        quotaNotificationService = new QuotaNotificationService(
                accountProducer, templateCacheService, notificationTrackingService);
    }

    @Test
    void testCheckAndNotifyThresholds_NullInput() {
        quotaNotificationService.checkAndNotifyThresholds(null, null, 0, 0)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verifyNoInteractions(templateCacheService);
    }

    @Test
    void testCheckAndNotifyThresholds_UnlimitedBalance() {
        UserSessionData userData = new UserSessionData();
        Balance balance = new Balance();
        balance.setInitialBalance(1000L);
        balance.setUnlimited(true);

        quotaNotificationService.checkAndNotifyThresholds(userData, balance, 1000, 500)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verifyNoInteractions(templateCacheService);
    }

    @Test
    void testCheckAndNotifyThresholds_ThresholdCrossedAndNotDuplicate() {
        // Setup Data
        UserSessionData userData = new UserSessionData();
        userData.setUserName("testUser");
        userData.setSuperTemplateId(101L);

        Balance balance = new Balance();
        balance.setBucketId("Data_Pack");
        balance.setInitialBalance(100L);
        balance.setUnlimited(false);

        ThresholdGlobalTemplates template = new ThresholdGlobalTemplates();
        template.setThreshold(80L);
        template.setMassage("Usage is {QUOTA_PERCENTAGE}% for {PLAN_NAME}");
        template.setParams(new String[]{"QUOTA_PERCENTAGE", "PLAN_NAME"});

        // Mocking Behaviors
        when(templateCacheService.getTemplatesBySuperTemplateId(101L))
                .thenReturn(Uni.createFrom().item(List.of(template)));

        when(notificationTrackingService.isDuplicateNotification(anyString(), anyLong(), anyString(), anyLong()))
                .thenReturn(Uni.createFrom().item(false));

        when(notificationTrackingService.markNotificationSent(anyString(), anyLong(), anyString(), anyLong()))
                .thenReturn(Uni.createFrom().voidItem());

        when(accountProducer.produceQuotaNotificationEvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        // Execute: Old usage 70% (quota 30), New usage 85% (quota 15). Threshold 80% crossed.
        quotaNotificationService.checkAndNotifyThresholds(userData, balance, 30, 15)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        // Verifications
        ArgumentCaptor<QuotaNotificationEvent> eventCaptor = ArgumentCaptor.forClass(QuotaNotificationEvent.class);
        verify(accountProducer).produceQuotaNotificationEvent(eventCaptor.capture());

        QuotaNotificationEvent event = eventCaptor.getValue();
        assertEquals("testUser", event.username());
        assertTrue(event.message().contains("80%"));
        assertTrue(event.message().contains("Data_Pack"));
    }

    @Test
    void testCheckAndNotifyThresholds_DuplicateNotification() {
        UserSessionData userData = new UserSessionData();
        userData.setSuperTemplateId(101L);
        Balance balance = new Balance();
        balance.setInitialBalance(100L);

        ThresholdGlobalTemplates template = new ThresholdGlobalTemplates();
        template.setThreshold(50L);

        when(templateCacheService.getTemplatesBySuperTemplateId(anyLong()))
                .thenReturn(Uni.createFrom().item(List.of(template)));

        // Mock as duplicate
        when(notificationTrackingService.isDuplicateNotification(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(true));

        quotaNotificationService.checkAndNotifyThresholds(userData, balance, 60, 40)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(accountProducer, never()).produceQuotaNotificationEvent(any());
    }


    @Test
    void testRefreshTemplateCache() {
        when(templateCacheService.refreshCache()).thenReturn(Uni.createFrom().voidItem());

        quotaNotificationService.refreshTemplateCache()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(templateCacheService).refreshCache();
    }


    @Test
    void testParseTemplateIds_InternalLogic() {
        // Since parseTemplateIds is private and unused, we test it via reflection
        // or by making it package-private. Here is the reflection approach:
        try {
            java.lang.reflect.Method method = QuotaNotificationService.class
                    .getDeclaredMethod("parseTemplateIds", String.class);
            method.setAccessible(true);

            List<Long> result = (List<Long>) method.invoke(quotaNotificationService, "1, 2, invalid, 3");

            assertEquals(3, result.size());
            assertEquals(1L, result.get(0));
            assertEquals(3L, result.get(2));

            List<Long> emptyResult = (List<Long>) method.invoke(quotaNotificationService, " ");
            assertTrue(emptyResult.isEmpty());
        } catch (Exception e) {
            fail("Reflection failed: " + e.getMessage());
        }
    }
}