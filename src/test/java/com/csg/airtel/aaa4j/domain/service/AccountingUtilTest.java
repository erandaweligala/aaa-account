package com.csg.airtel.aaa4j.domain.service;


import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class AccountingUtilTest {

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
    void testFindBalanceWithHighestPriority_SingleBalance() {
        List<Balance> balances = new ArrayList<>();
        Balance balance = createBalance(1L, 1000L);
        balances.add(balance);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals(balance.getBucketId(), result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPriority_EmptyList() {
        List<Balance> balances = new ArrayList<>();

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNull(result);
    }

    @Test
    void testFindBalanceWithHighestPriority_MultiplePriorities() {
        List<Balance> balances = new ArrayList<>();
        Balance balance1 = createBalance(1L, 1000L);
        balance1.setPriority(2L);
        Balance balance2 = createBalance(2L, 2000L);
        balance2.setPriority(1L);
        balances.add(balance1);
        balances.add(balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals(1L, result.getPriority());
    }

    @Test
    void testIsWithinTimeWindow_ValidWindow() {
        boolean result = accountingUtil.isWithinTimeWindow("0-24");
        assertTrue(result);
    }

    @Test
    void testIsWithinTimeWindow_InvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("invalid");
        });
    }

    @Test
    void testIsWithinTimeWindow_NullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow(null);
        });
    }

    @Test
    void testIsWithinTimeWindow_EmptyString() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("");
        });
    }

    @Test
    void testCalculateConsumptionInWindow() {
        Balance balance = createBalance(1L, 1000L);
        balance.setServiceStartDate(LocalDateTime.now().minusDays(15));
        balance.setConsumptionHistory(new ArrayList<>());

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

        assertEquals(0L, consumption);
    }

    @Test
    void testClearTemporalCache() {
        accountingUtil.clearTemporalCache();
        // Verify it doesn't throw exception and can be called multiple times
        accountingUtil.clearTemporalCache();
        assertDoesNotThrow(() -> accountingUtil.clearTemporalCache());
    }

    @Test
    void testPrepareGroupDataWithSession_WithNullExistingData() {
        Balance balance = createBalance(1L, 1000L);
        Session session = createTestSession();
        AccountingRequestDto request = createStopRequest();
        UserSessionData currentUserData = UserSessionData.builder()
            .userName("testuser")
            .balance(new ArrayList<>())
            .sessions(new ArrayList<>())
            .build();

        UserSessionData result = accountingUtil.prepareGroupDataWithSession(
            null, balance, session, request, currentUserData
        );

        assertNotNull(result);
        assertNotNull(result.getBalance());
        assertNotNull(result.getSessions());
        assertTrue(result.getSessions().contains(session));
    }

    @Test
    void testPrepareGroupDataWithSession_WithExistingGroupData() {
        Balance balance = createBalance(1L, 1000L);
        balance.setGroup(true);
        Session session = createTestSession();
        AccountingRequestDto request = createInterimRequest();

        List<Session> existingSessions = new ArrayList<>();
        existingSessions.add(createTestSession("existing-session"));

        UserSessionData existingGroupData = UserSessionData.builder()
            .userName("group-user")
            .balance(new ArrayList<>())
            .sessions(existingSessions)
            .groupId("group-123")
            .build();

        UserSessionData currentUserData = UserSessionData.builder()
            .userName("testuser")
            .balance(new ArrayList<>())
            .sessions(new ArrayList<>())
            .build();

        UserSessionData result = accountingUtil.prepareGroupDataWithSession(
            existingGroupData, balance, session, request, currentUserData
        );

        assertNotNull(result);
        assertNotNull(result.getBalance());
        assertTrue(result.getBalance().contains(balance));
        assertTrue(result.getSessions().size() >= 1);
    }

    @Test
    void testPrepareGroupDataWithSession_StopRequest() {
        Balance balance = createBalance(1L, 1000L);
        balance.setGroup(true);
        Session session = createTestSession();
        AccountingRequestDto request = createStopRequest();

        UserSessionData existingGroupData = UserSessionData.builder()
            .userName("group-user")
            .balance(new ArrayList<>())
            .sessions(new ArrayList<>())
            .build();

        UserSessionData currentUserData = UserSessionData.builder()
            .userName("testuser")
            .balance(new ArrayList<>())
            .sessions(new ArrayList<>())
            .build();

        UserSessionData result = accountingUtil.prepareGroupDataWithSession(
            existingGroupData, balance, session, request, currentUserData
        );

        assertNotNull(result);
        // For STOP requests, session should not be added
        assertFalse(result.getSessions().contains(session));
    }

    @Test
    void testPrepareGroupDataWithSession_NullSession() {
        Balance balance = createBalance(1L, 1000L);
        AccountingRequestDto request = createInterimRequest();

        UserSessionData existingGroupData = UserSessionData.builder()
            .userName("group-user")
            .balance(new ArrayList<>())
            .sessions(new ArrayList<>())
            .build();

        UserSessionData currentUserData = UserSessionData.builder()
            .userName("testuser")
            .balance(new ArrayList<>())
            .sessions(new ArrayList<>())
            .build();

        UserSessionData result = accountingUtil.prepareGroupDataWithSession(
            existingGroupData, balance, null, request, currentUserData
        );

        assertNotNull(result);
        assertNotNull(result.getBalance());
        assertTrue(result.getBalance().contains(balance));
    }

    private Balance createBalance(Long priority, Long quota) {
        Balance balance = new Balance();
        balance.setBucketId("bucket-" + priority);
        balance.setServiceId("service-1");
        balance.setPriority(priority);
        balance.setQuota(quota);
        balance.setInitialBalance(2000L);
        balance.setServiceExpiry(LocalDateTime.now().plusDays(30));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        balance.setServiceStartDate(LocalDateTime.now().minusDays(1));
        balance.setServiceStatus("Active");
        balance.setTimeWindow("0-24");
        balance.setGroup(false);
        balance.setBucketUsername("testuser");
        return balance;
    }

    private com.csg.airtel.aaa4j.domain.model.session.Session createTestSession() {
        return createTestSession("session-123");
    }

    private com.csg.airtel.aaa4j.domain.model.session.Session createTestSession(String sessionId) {
        return new com.csg.airtel.aaa4j.domain.model.session.Session(
            sessionId,
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            0,
            0L,
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

    private AccountingRequestDto createInterimRequest() {
        return new AccountingRequestDto(
            "testuser",
            "session-123",
            AccountingRequestDto.ActionType.INTERIM_UPDATE,
            0,
            0L,
            0L,
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
            0,
            0L,
            0L,
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
