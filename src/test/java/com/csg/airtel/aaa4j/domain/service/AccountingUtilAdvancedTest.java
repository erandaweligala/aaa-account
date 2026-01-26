package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.UpdateResult;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.ConsumptionRecord;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountingUtilAdvancedTest {

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private CacheClient cacheClient;

    @Mock
    private COAService coaService;

    @Mock
    private QuotaNotificationService quotaNotificationService;

    private AccountingUtil accountingUtil;

    @BeforeEach
    void setUp() {
        accountingUtil = new AccountingUtil(
            accountProducer, cacheClient, coaService, quotaNotificationService
        );
    }

    @Test
    void testUpdateSessionAndBalance_WithoutGroupData_Success() {
        UserSessionData userData = createUserData();
        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
    }

    @Test
    void testUpdateSessionAndBalance_WithGroupData() {
        UserSessionData userData = createUserData();
        userData.setGroupId("group-123");
        Session session = createSession();
        AccountingRequestDto request = createRequest();

        UserSessionData groupData = createGroupData();
        when(cacheClient.getUserData("group-123")).thenReturn(Uni.createFrom().item(groupData));
        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
    }

    @Test
    void testUpdateSessionAndBalance_NoBalanceFound() {
        UserSessionData userData = createUserData();
        userData.setBalance(new ArrayList<>()); // Empty balances
        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(coaService.clearAllSessionsAndSendCOA(any(), anyString(), any()))
            .thenReturn(Uni.createFrom().item(userData));

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertFalse(result.success());
    }

    @Test
    void testCalculateConsumptionInWindow_WithHistory() {
        Balance balance = createBalance();
        balance.setServiceStartDate(LocalDateTime.now().minusDays(15));

        List<ConsumptionRecord> history = new ArrayList<>();
        LocalDate today = LocalDate.now();
        history.add(new ConsumptionRecord(today.minusDays(5), 100L, 1));
        history.add(new ConsumptionRecord(today.minusDays(3), 200L, 1));
        history.add(new ConsumptionRecord(today.minusDays(1), 150L, 1));
        balance.setConsumptionHistory(history);

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

        assertTrue(consumption > 0);
    }

    @Test
    void testCalculateConsumptionInWindow_NoServiceStartDate() {
        Balance balance = createBalance();
        balance.setServiceStartDate(null);

        List<ConsumptionRecord> history = new ArrayList<>();
        LocalDate today = LocalDate.now();
        history.add(new ConsumptionRecord(today.minusDays(5), 100L, 1));
        balance.setConsumptionHistory(history);

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

        assertEquals(100L, consumption);
    }

    @Test
    void testIsWithinTimeWindow_SpansMidnight() {
        // Test window that spans midnight (e.g., "22-6")
        boolean result = accountingUtil.isWithinTimeWindow("0-12");
        assertNotNull(result);
    }

    @Test
    void testIsWithinTimeWindow_SingleHour() {
        boolean result = accountingUtil.isWithinTimeWindow("8-9");
        assertNotNull(result);
    }

    @Test
    void testIsWithinTimeWindow_InvalidFormat_TooManyParts() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("8-12-16");
        });
    }

    @Test
    void testFindBalanceWithHighestPriority_WithSpecificBucketId() {
        List<Balance> balances = new ArrayList<>();
        Balance balance1 = createBalanceWithId("bucket-1", 2L);
        Balance balance2 = createBalanceWithId("bucket-2", 1L);
        balances.add(balance1);
        balances.add(balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, "bucket-1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals("bucket-1", result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPriority_NoEligibleBalances() {
        List<Balance> balances = new ArrayList<>();
        Balance balance = createBalance();
        balance.setQuota(0L); // No quota
        balance.setUnlimited(false);
        balances.add(balance);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNull(result);
    }

    @Test
    void testFindBalanceWithHighestPriority_ExpiredBalance() {
        List<Balance> balances = new ArrayList<>();
        Balance balance = createBalance();
        balance.setServiceExpiry(LocalDateTime.now().minusDays(1)); // Expired
        balances.add(balance);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNull(result);
    }

    @Test
    void testFindBalanceWithHighestPriority_ConsumptionLimitExceeded() {
        List<Balance> balances = new ArrayList<>();
        Balance balance = createBalance();
        balance.setConsumptionLimit(100L);
        balance.setConsumptionLimitWindow(30L);

        List<ConsumptionRecord> history = new ArrayList<>();
        LocalDate today = LocalDate.now();
        history.add(new ConsumptionRecord(today, 150L, 1)); // Exceeds limit
        balance.setConsumptionHistory(history);
        balance.setServiceStartDate(LocalDateTime.now().minusDays(15));

        balances.add(balance);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNull(result);
    }

    @Test
    void testFindBalanceWithHighestPriority_SamePriorityDifferentExpiry() {
        List<Balance> balances = new ArrayList<>();

        Balance balance1 = createBalanceWithId("bucket-1", 1L);
        balance1.setBucketExpiryDate(LocalDateTime.now().plusDays(10));

        Balance balance2 = createBalanceWithId("bucket-2", 1L);
        balance2.setBucketExpiryDate(LocalDateTime.now().plusDays(5)); // Earlier expiry

        balances.add(balance1);
        balances.add(balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals("bucket-2", result.getBucketId()); // Earlier expiry should be selected
    }

    @Test
    void testPrepareGroupDataWithSession_NewGroupData() {
        Balance balance = createBalance();
        Session session = createSession();
        AccountingRequestDto request = createRequest();
        UserSessionData currentUserData = createUserData();

        UserSessionData result = accountingUtil.prepareGroupDataWithSession(
            null, balance, session, request, currentUserData
        );

        assertNotNull(result);
        assertNotNull(result.getBalance());
        assertNotNull(result.getSessions());
    }

    @Test
    void testPrepareGroupDataWithSession_ExistingGroupData() {
        Balance balance = createBalance();
        Session session = createSession();
        AccountingRequestDto request = createRequest();
        UserSessionData existingGroupData = createGroupData();
        UserSessionData currentUserData = createUserData();

        UserSessionData result = accountingUtil.prepareGroupDataWithSession(
            existingGroupData, balance, session, request, currentUserData
        );

        assertNotNull(result);
    }

    @Test
    void testPrepareGroupDataWithSession_StopAction() {
        Balance balance = createBalance();
        Session session = createSession();
        AccountingRequestDto request = createStopRequest();
        UserSessionData currentUserData = createUserData();

        UserSessionData result = accountingUtil.prepareGroupDataWithSession(
            null, balance, session, request, currentUserData
        );

        assertNotNull(result);
        // Session should not be added for STOP action
    }

    @Test
    void testIsWithinTimeWindow_Hour24() {
        boolean result = accountingUtil.isWithinTimeWindow("0-24");
        assertTrue(result);
    }

    @Test
    void testIsWithinTimeWindow_InvalidHour() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("0-25");
        });
    }

    @Test
    void testIsWithinTimeWindow_NegativeHour() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("-1-12");
        });
    }

    @Test
    void testUpdateSessionAndBalance_ConcurrencyCheckIndividual() {
        UserSessionData userData = createUserData();
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSessionWithUser("session-1", "testuser"));
        userData.setSessions(sessions);
        userData.setConcurrency(1L); // Limit to 1

        Session newSession = createSessionWithUser("session-2", "testuser");
        newSession.setNasPortId("port-1");

        AccountingRequestDto request = createRequest();
        request = new AccountingRequestDto(
            request.username(), "session-2", AccountingRequestDto.ActionType.INTERIM_UPDATE,
            request.sessionTime(), request.inputOctets(), request.outputOctets(),
            request.inputGigaWords(), request.outputGigaWords(), request.framedIPAddress(),
            request.nasIP(), request.nasIdentifier(), "port-1", 0, request.timestamp()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        accountingUtil.updateSessionAndBalance(userData, newSession, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();
    }

    @Test
    void testUpdateSessionAndBalance_QuotaDepleted() {
        UserSessionData userData = createUserData();
        Balance balance = userData.getBalance().get(0);
        balance.setQuota(10L); // Very low quota
        balance.setUnlimited(false);

        Session session = createSession();
        session.setPreviousTotalUsageQuotaValue(0L);

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-123", AccountingRequestDto.ActionType.INTERIM_UPDATE,
            1000, 5000L, 5000L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(coaService.clearAllSessionsAndSendCOA(any(), anyString(), any()))
            .thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceDBWriteEvent(any()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaService).clearAllSessionsAndSendCOA(any(), anyString(), any());
    }

    @Test
    void testUpdateSessionAndBalance_UnlimitedBucket() {
        UserSessionData userData = createUserData();
        Balance balance = userData.getBalance().get(0);
        balance.setUnlimited(true);
        balance.setQuota(0L);

        Session session = createSession();
        session.setPreviousTotalUsageQuotaValue(500L);

        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void testUpdateSessionAndBalance_BucketChange() {
        UserSessionData userData = createUserData();

        // Add second balance with higher priority
        Balance balance2 = createBalanceWithId("bucket-2", 0L); // Lower number = higher priority
        balance2.setQuota(2000L);
        userData.getBalance().add(balance2);

        Session session = createSession();
        session.setPreviousUsageBucketId("bucket-1");
        session.setPreviousTotalUsageQuotaValue(500L);

        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(coaService.clearAllSessionsAndSendCOA(any(), anyString(), any()))
            .thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceDBWriteEvent(any()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
    }

    @Test
    void testUpdateSessionAndBalance_ConsumptionLimitExceeded() {
        UserSessionData userData = createUserData();
        Balance balance = userData.getBalance().get(0);
        balance.setConsumptionLimit(1000L);
        balance.setConsumptionLimitWindow(30L);
        balance.setServiceStartDate(LocalDateTime.now().minusDays(15));

        // Add consumption history that exceeds the limit
        List<ConsumptionRecord> history = new ArrayList<>();
        history.add(new ConsumptionRecord(LocalDate.now(), 950L, 1));
        balance.setConsumptionHistory(history);

        Session session = createSession();
        session.setPreviousTotalUsageQuotaValue(0L);

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-123", AccountingRequestDto.ActionType.INTERIM_UPDATE,
            1000, 100L, 100L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(coaService.clearAllSessionsAndSendCOA(any(), anyString(), any()))
            .thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceDBWriteEvent(any()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaService).clearAllSessionsAndSendCOA(any(), anyString(), any());
    }

    @Test
    void testUpdateSessionAndBalance_CacheUpdateFailure() {
        UserSessionData userData = createUserData();
        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success()); // Should still succeed even if cache update fails
    }

    @Test
    void testUpdateSessionAndBalance_StartAction() {
        UserSessionData userData = createUserData();
        Session session = createSession();

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-123", AccountingRequestDto.ActionType.START,
            0, 0L, 0L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void testUpdateSessionAndBalance_StopAction() {
        UserSessionData userData = createUserData();
        Session session = createSession();

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-123", AccountingRequestDto.ActionType.STOP,
            1000, 500L, 500L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void testUpdateSessionAndBalance_GroupBucketWithConcurrency() {
        UserSessionData userData = createUserData();
        userData.setGroupId("group-123");

        UserSessionData groupData = createGroupData();
        Balance groupBalance = groupData.getBalance().get(0);
        groupBalance.setGroup(true);

        Session session = createSessionWithUser("session-123", "testuser");
        session.setUserConcurrency(2L);

        AccountingRequestDto request = createRequest();

        when(cacheClient.getUserData("group-123")).thenReturn(Uni.createFrom().item(groupData));
        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
    }

    @Test
    void testUpdateSessionAndBalance_SpecificBucketId() {
        UserSessionData userData = createUserData();

        // Add multiple balances
        Balance balance2 = createBalanceWithId("bucket-2", 0L);
        balance2.setQuota(3000L);
        userData.getBalance().add(balance2);

        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        // Specify bucket-2 explicitly
        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, "bucket-2")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals("bucket-2", result.bucketId());
    }

    @Test
    void testUpdateSessionAndBalance_COAServiceFailure() {
        UserSessionData userData = createUserData();
        userData.getBalance().clear(); // No balance to trigger COA

        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(coaService.clearAllSessionsAndSendCOA(any(), anyString(), any()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("COA error")));

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertFalse(result.success());
    }

    @Test
    void testUpdateSessionAndBalance_GroupDataFetchFailure() {
        UserSessionData userData = createUserData();
        userData.setGroupId("group-123");

        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(cacheClient.getUserData("group-123"))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));
        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        // Should still succeed with user data only
        assertTrue(result.success());
    }

    @Test
    void testUpdateSessionAndBalance_SessionTimeUnchanged() {
        UserSessionData userData = createUserData();
        userData.setGroupId("group-123");

        UserSessionData groupData = createGroupData();
        Balance groupBalance = groupData.getBalance().get(0);
        groupBalance.setGroup(true);

        Session existingSession = createSession();
        existingSession.setSessionTime(2000); // Higher session time
        groupData.getSessions().add(existingSession);

        Session session = createSession();
        session.setSessionTime(1500); // Lower session time

        AccountingRequestDto request = createRequest();

        when(cacheClient.getUserData("group-123")).thenReturn(Uni.createFrom().item(groupData));

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("Session time unchanged", result.message());
    }

    @Test
    void testUpdateSessionAndBalance_SessionAbsoluteTimeoutExceeded() {
        UserSessionData userData = createUserData();

        Session session = createSession();
        session.setSessionInitiatedTime(LocalDateTime.now().minusHours(5));
        session.setSessionStartTime(LocalDateTime.now().minusHours(5));
        session.setAbsoluteTimeOut("3600"); // 1 hour timeout (expired)

        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(coaService.clearAllSessionsAndSendCOA(any(), anyString(), anyString()))
            .thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceDBWriteEvent(any()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaService).clearAllSessionsAndSendCOA(any(), anyString(), eq(session.getSessionId()));
    }

    @Test
    void testUpdateSessionAndBalance_NullSessionInitiatedTime() {
        UserSessionData userData = createUserData();

        Session session = createSession();
        session.setSessionInitiatedTime(null);
        session.setAbsoluteTimeOut("3600");

        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
        // Should not trigger timeout disconnect
        verify(coaService, never()).clearAllSessionsAndSendCOA(any(), anyString(), anyString());
    }

    @Test
    void testUpdateSessionAndBalance_DBWriteEventFailure() {
        UserSessionData userData = createUserData();
        Balance balance = userData.getBalance().get(0);
        balance.setQuota(10L);
        balance.setUnlimited(false);

        Session session = createSession();
        session.setPreviousTotalUsageQuotaValue(0L);

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-123", AccountingRequestDto.ActionType.INTERIM_UPDATE,
            1000, 5000L, 5000L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(coaService.clearAllSessionsAndSendCOA(any(), anyString(), any()))
            .thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceDBWriteEvent(any()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("DB write error")));

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(accountProducer).produceDBWriteEvent(any());
    }

    @Test
    void testUpdateSessionAndBalance_NotificationServiceFailure() {
        UserSessionData userData = createUserData();
        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Notification error")));
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        // Should still succeed despite notification failure
        assertTrue(result.success());
    }

    @Test
    void testUpdateSessionAndBalance_BucketChangeNoPreviousBalance() {
        UserSessionData userData = createUserData();

        Balance balance2 = createBalanceWithId("bucket-2", 0L);
        balance2.setQuota(2000L);
        userData.getBalance().add(balance2);

        Session session = createSession();
        session.setPreviousUsageBucketId("bucket-999"); // Non-existent bucket
        session.setPreviousTotalUsageQuotaValue(500L);

        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void testUpdateSessionAndBalance_GroupConcurrencyExceeded() {
        UserSessionData userData = createUserData();
        userData.setGroupId("group-123");

        UserSessionData groupData = createGroupData();
        Balance groupBalance = groupData.getBalance().get(0);
        groupBalance.setGroup(true);

        // Add multiple sessions to exceed concurrency
        groupData.getSessions().add(createSessionWithUser("session-1", "testuser"));
        groupData.getSessions().add(createSessionWithUser("session-2", "testuser"));
        groupData.getSessions().add(createSessionWithUser("session-3", "testuser"));

        Session newSession = createSessionWithUser("session-new", "testuser");
        newSession.setUserConcurrency(2L); // Limit to 2
        newSession.setNasPortId("port-new");

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-new", AccountingRequestDto.ActionType.START,
            0, 0L, 0L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-new", 0, LocalDateTime.now()
        );

        when(cacheClient.getUserData("group-123")).thenReturn(Uni.createFrom().item(groupData));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, newSession, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaService).produceAccountingResponseEvent(any(), any(), anyString());
    }

    @Test
    void testUpdateSessionAndBalance_MatchingNasPortId() {
        UserSessionData userData = createUserData();

        Session existingSession = createSessionWithUser("session-1", "testuser");
        existingSession.setNasPortId("port-1");
        userData.getSessions().clear();
        userData.getSessions().add(existingSession);
        userData.setConcurrency(1L);

        Session newSession = createSessionWithUser("session-2", "testuser");
        newSession.setNasPortId("port-1"); // Same port

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-2", AccountingRequestDto.ActionType.START,
            0, 0L, 0L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, newSession, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
    }

    @Test
    void testUpdateSessionAndBalance_NegativeUsageDelta() {
        UserSessionData userData = createUserData();

        Session session = createSession();
        session.setPreviousTotalUsageQuotaValue(1000L); // Previous usage higher than current

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-123", AccountingRequestDto.ActionType.INTERIM_UPDATE,
            1000, 500L, 500L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
        // Usage delta should be clamped to 0
    }

    @Test
    void testUpdateSessionAndBalance_ConsumptionHistoryAggregation() {
        UserSessionData userData = createUserData();
        Balance balance = userData.getBalance().get(0);
        balance.setConsumptionLimit(5000L);
        balance.setConsumptionLimitWindow(30L);
        balance.setServiceStartDate(LocalDateTime.now().minusDays(15));

        // Add existing consumption history
        List<ConsumptionRecord> history = new ArrayList<>();
        history.add(new ConsumptionRecord(LocalDate.now(), 500L, 1));
        balance.setConsumptionHistory(history);

        Session session = createSession();
        session.setPreviousTotalUsageQuotaValue(0L);

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-123", AccountingRequestDto.ActionType.INTERIM_UPDATE,
            1000, 100L, 100L, 0, 0, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
        // Consumption should be aggregated into existing daily record
        assertEquals(1, balance.getConsumptionHistory().size());
    }

    @Test
    void testUpdateSessionAndBalance_WithGigawords() {
        UserSessionData userData = createUserData();

        Session session = createSession();
        session.setPreviousTotalUsageQuotaValue(0L);

        AccountingRequestDto request = new AccountingRequestDto(
            "testuser", "session-123", AccountingRequestDto.ActionType.INTERIM_UPDATE,
            1000, 1000L, 1000L, 1, 1, "192.168.1.1", "10.0.0.1",
            "nas-id", "port-1", 0, LocalDateTime.now()
        );

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
        // Should calculate total usage including gigawords
    }

    @Test
    void testUpdateSessionAndBalance_NullBalanceList() {
        UserSessionData userData = createUserData();
        userData.setBalance(null);

        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(coaService.clearAllSessionsAndSendCOA(any(), anyString(), any()))
            .thenReturn(Uni.createFrom().item(userData));

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertFalse(result.success());
        verify(coaService).clearAllSessionsAndSendCOA(any(), anyString(), any());
    }

    @Test
    void testUpdateSessionAndBalance_NullSessionsList() {
        UserSessionData userData = createUserData();
        userData.setSessions(null);

        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void testUpdateSessionAndBalance_MultipleBalancesSamePriority() {
        UserSessionData userData = createUserData();

        // Add multiple balances with same priority
        Balance balance2 = createBalanceWithId("bucket-2", 1L);
        balance2.setQuota(3000L);
        balance2.setBucketExpiryDate(LocalDateTime.now().plusDays(20)); // Earlier expiry
        userData.getBalance().add(balance2);

        Session session = createSession();
        AccountingRequestDto request = createRequest();

        when(quotaNotificationService.checkAndNotifyThresholds(any(), any(), anyLong(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, session, request, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.success());
        // Should select balance with earlier expiry when priorities are same
    }

    private UserSessionData createUserData() {
        List<Balance> balances = new ArrayList<>();
        balances.add(createBalance());

        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession());

        return UserSessionData.builder()
            .userName("testuser")
            .balance(balances)
            .sessions(sessions)
            .concurrency(2L)
            .groupId(AppConstant.DEFAULT_GROUP_ID)
            .build();
    }

    private UserSessionData createGroupData() {
        List<Balance> balances = new ArrayList<>();
        Balance balance = createBalance();
        balance.setGroup(true);
        balances.add(balance);

        return UserSessionData.builder()
            .userName("groupuser")
            .balance(balances)
            .sessions(new ArrayList<>())
            .groupId("group-123")
            .build();
    }

    private Balance createBalance() {
        Balance balance = new Balance();
        balance.setBucketId("bucket-1");
        balance.setServiceId("service-1");
        balance.setQuota(1000L);
        balance.setInitialBalance(2000L);
        balance.setServiceExpiry(LocalDateTime.now().plusDays(30));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        balance.setServiceStartDate(LocalDateTime.now().minusDays(1));
        balance.setServiceStatus("Active");
        balance.setPriority(1L);
        balance.setTimeWindow("0-24");
        balance.setBucketUsername("testuser");
        balance.setGroup(false);
        balance.setUnlimited(false);
        return balance;
    }

    private Balance createBalanceWithId(String bucketId, Long priority) {
        Balance balance = createBalance();
        balance.setBucketId(bucketId);
        balance.setPriority(priority);
        return balance;
    }

    private Session createSession() {
        return new Session(
            "session-123",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            500,
            500L,
            "192.168.1.1",
            "10.0.0.1",
            "port-1",
            false,
            0,
            "service-1",
            "testuser",
            "bucket-1",
            null,
            null,
            0
        );
    }

    private Session createSessionWithUser(String sessionId, String username) {
        return new Session(
            sessionId,
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            500,
            500L,
            "192.168.1.1",
            "10.0.0.1",
            "port-1",
            false,
            0,
            "service-1",
            username,
            "bucket-1",
            null,
            null,
            2L
        );
    }

    private AccountingRequestDto createRequest() {
        return new AccountingRequestDto(
            "testuser",
            "session-123",
            AccountingRequestDto.ActionType.INTERIM_UPDATE,
            1000,
            500L,
            500L,
            0,
            0,
            "192.168.1.1",
            "10.0.0.1",
            "nas-id",
            "port-1",
            0,
            LocalDateTime.now()
        );
    }

    private AccountingRequestDto createStopRequest() {
        return new AccountingRequestDto(
            "testuser",
            "session-123",
            AccountingRequestDto.ActionType.STOP,
            1000,
            500L,
            500L,
            0,
            0,
            "192.168.1.1",
            "10.0.0.1",
            "nas-id",
            "port-1",
            0,
            LocalDateTime.now()
        );
    }
}
