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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuotaNotificationServiceTest {

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private MessageTemplateCacheService templateCacheService;

    @Mock
    private NotificationTrackingService notificationTrackingService;

    private QuotaNotificationService quotaNotificationService;

    @BeforeEach
    void setUp() {
        quotaNotificationService = new QuotaNotificationService(
            accountProducer, templateCacheService, notificationTrackingService
        );
    }

    @Test
    void testCheckAndNotifyThresholds_NullUserData() {
        quotaNotificationService.checkAndNotifyThresholds(null, null, 1000L, 500L)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(templateCacheService, never()).getTemplatesBySuperTemplateId(any());
    }

    @Test
    void testCheckAndNotifyThresholds_UnlimitedBalance() {
        UserSessionData userData = createUserData();
        Balance balance = createBalance();
        balance.setUnlimited(true);

        quotaNotificationService.checkAndNotifyThresholds(userData, balance, 1000L, 500L)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(templateCacheService, never()).getTemplatesBySuperTemplateId(any());
    }

    @Test
    void testCheckAndNotifyThresholds_NoTemplates() {
        UserSessionData userData = createUserData();
        Balance balance = createBalance();
        when(templateCacheService.getTemplatesBySuperTemplateId(anyLong()))
            .thenReturn(Uni.createFrom().item(new ArrayList<>()));

        quotaNotificationService.checkAndNotifyThresholds(userData, balance, 1000L, 500L)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(templateCacheService).getTemplatesBySuperTemplateId(anyLong());
        verify(accountProducer, never()).produceQuotaNotificationEvent(any());
    }

    @Test
    void testCheckAndNotifyThresholds_ThresholdCrossed() {
        UserSessionData userData = createUserData();
        Balance balance = createBalance();
        List<ThresholdGlobalTemplates> templates = createTemplates();

        when(templateCacheService.getTemplatesBySuperTemplateId(anyLong()))
            .thenReturn(Uni.createFrom().item(templates));
        when(notificationTrackingService.isDuplicateNotification(anyString(), anyLong(), anyString(), anyLong()))
            .thenReturn(Uni.createFrom().item(false));
        when(notificationTrackingService.markNotificationSent(anyString(), anyLong(), anyString(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceQuotaNotificationEvent(any(QuotaNotificationEvent.class)))
            .thenReturn(Uni.createFrom().voidItem());

        quotaNotificationService.checkAndNotifyThresholds(userData, balance, 1000L, 500L)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(templateCacheService).getTemplatesBySuperTemplateId(anyLong());
    }

    @Test
    void testRefreshTemplateCache() {
        when(templateCacheService.refreshCache()).thenReturn(Uni.createFrom().voidItem());

        quotaNotificationService.refreshTemplateCache()
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(templateCacheService).refreshCache();
    }

    private UserSessionData createUserData() {
        return UserSessionData.builder()
            .userName("testuser")
            .superTemplateId(1L)
            .build();
    }

    private Balance createBalance() {
        Balance balance = new Balance();
        balance.setBucketId("bucket-123");
        balance.setInitialBalance(2000L);
        balance.setQuota(1000L);
        balance.setUnlimited(false);
        return balance;
    }

    private List<ThresholdGlobalTemplates> createTemplates() {
        List<ThresholdGlobalTemplates> templates = new ArrayList<>();
        templates.add(new ThresholdGlobalTemplates(
            1L, 100L, 80L, "Test message", new String[]{"MOCN"}
        ));
        return templates;
    }
}
