package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;

import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.repository.UserBucketRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StartHandlerTest {

    private CacheClient utilCache;
    private UserBucketRepository userRepository;
    private AccountProducer accountProducer;
    private AccountingUtil accountingUtil;
    private SessionLifecycleManager sessionLifecycleManager;
    private COAService coaService;
    private StartHandler startHandler;


    @BeforeEach
    void setUp() {
        utilCache = mock(CacheClient.class);
        userRepository = mock(UserBucketRepository.class);
        accountProducer = mock(AccountProducer.class);
        accountingUtil = mock(AccountingUtil.class);
        sessionLifecycleManager = mock(SessionLifecycleManager.class);
        coaService = mock(COAService.class);

        startHandler = new StartHandler(utilCache, userRepository, accountProducer,
                accountingUtil, sessionLifecycleManager, coaService);
    }

    private AccountingRequestDto createRequest(String user, String sessionId) {
        return new AccountingRequestDto(
                "evt-1", sessionId, "127.0.0.1", user,
                AccountingRequestDto.ActionType.START, 0, 0, 0,
                Instant.now(), "port-1", "10.0.0.1", 0, 0, 0, "nas-1"
        );
    }

    @Test
    void testProcessAccountingStart_NewUser_NoBuckets_TriggersDisconnect() {
        AccountingRequestDto request = createRequest("newUser", "sess-1");

        when(utilCache.getUserData("newUser")).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName("newUser")).thenReturn(Uni.createFrom().item(List.of()));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString())).thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-1").await().indefinitely();

        verify(coaService).produceAccountingResponseEvent(any(), any(), eq("newUser"));
    }

    @Test
    void testProcessAccountingStart_ExistingUser_DuplicateSession_ReturnsEarly() {
        AccountingRequestDto request = createRequest("user1", "sess-existing");
        UserSessionData data = new UserSessionData();
        Session s = new Session();
        s.setSessionId("sess-existing");
        data.setSessions(List.of(s));
        data.setBalance(List.of(new Balance()));

        when(utilCache.getUserData("user1")).thenReturn(Uni.createFrom().item(data));

        startHandler.processAccountingStart(request, "trace-1").await().indefinitely();

        verify(accountingUtil, never()).findBalanceWithHighestPriority(any(), any());
    }

    @Test
    void testProcessAccountingStart_BalanceExhausted_TriggersDisconnect() {
        AccountingRequestDto request = createRequest("user1", "sess-2");
        UserSessionData data = new UserSessionData();
        data.setBalance(new ArrayList<>()); // Empty balance = 0 available

        when(utilCache.getUserData("user1")).thenReturn(Uni.createFrom().item(data));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString())).thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-1").await().indefinitely();

        verify(coaService).produceAccountingResponseEvent(any(), any(), eq("user1"));
    }

    @Test
    void testProcessAccountingStart_CircuitBreakerOpen_Recovers() {
        AccountingRequestDto request = createRequest("user1", "sess-2");
        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().failure(new CircuitBreakerOpenException()));

        startHandler.processAccountingStart(request, "trace-1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();
    }


    @Test
    void testNewUser_GroupDataCreation_Success() {
        AccountingRequestDto request = createRequest("user1", "sess-1");
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketUser("admin"); // Becomes groupId
        bucket.setCurrentBalance(500L);
        bucket.setUserStatus("ACTIVE");

        Balance balance = new Balance();
        balance.setQuota(500L);
        balance.setBucketUsername("admin");

        when(utilCache.getUserData("user1")).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName("user1")).thenReturn(Uni.createFrom().item(List.of(bucket)));
        when(accountingUtil.findBalanceWithHighestPriority(any(), any())).thenReturn(Uni.createFrom().item(balance));
        when(utilCache.storeUserData(eq("user1"), any(), anyString())).thenReturn(Uni.createFrom().voidItem());
        when(utilCache.getUserData("admin")).thenReturn(Uni.createFrom().nullItem()); // Group data doesn't exist yet
        when(utilCache.storeUserData(eq("admin"), any(), anyString())).thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any())).thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-1").await().indefinitely();

        verify(utilCache).storeUserData(eq("admin"), any(), eq("user1"));
    }

    private AccountingRequestDto createReq(String user, String sessionId) {
        return new AccountingRequestDto("evt-1", sessionId, "1.1.1.1", user,
                AccountingRequestDto.ActionType.START, 0, 0, 0, Instant.now(), "port-1", "10.0.0.1", 0, 0, 0, "nas-1");
    }

    // 1. Coverage for: handleExistingUserSession -> Group Logic -> updateUserCacheOnly
    @Test
    void testExistingSession_IndividualBalance_Success() {


        Balance b = new Balance(); b.setBucketUsername("admin");
        b.setQuota(100L);
        b.setInitialBalance(500L);
        b.setGroup(false);
        b.setUnlimited(false);
        AccountingRequestDto req = createReq("user1", "s1");
        UserSessionData usd = new UserSessionData();
        usd.setUserName("user1");
        usd.setConcurrency(5);
        usd.setBalance(new ArrayList<>(List.of(b)));
        usd.setSessions(new ArrayList<>());

        when(utilCache.getUserData("user1")).thenReturn(Uni.createFrom().item(usd));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), any())).thenReturn(Uni.createFrom().item(usd.getBalance().get(0)));
        when(utilCache.updateUserAndRelatedCaches(anyString(), any(), anyString())).thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any())).thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(req, "t1").await().indefinitely();

        verify(utilCache).updateUserAndRelatedCaches(eq("user1"), any(), eq("user1"));
        verify(accountProducer).produceAccountingCDREvent(any()); // generateAndSendCDR
    }

    // 2. Coverage for: isHighestPriorityGroupBalance -> updateBothCaches


    // 3. Coverage for: handleNewUserSession -> processServiceBuckets -> Zero Quota
    @Test
    void testNewUser_ZeroQuota_Disconnect() {
        AccountingRequestDto req = createReq("user2", "s2");
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketUser("user2");
        bucket.setCurrentBalance(0); // Zero Quota
        Balance b = new Balance(); b.setBucketUsername("admin");
        b.setQuota(0L);
        b.setInitialBalance(500L);
        b.setGroup(false);
        b.setUnlimited(false);
        when(utilCache.getUserData("user2")).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName("user2")).thenReturn(Uni.createFrom().item(List.of(bucket)));
        when(accountingUtil.findBalanceWithHighestPriority(any(), any())).thenReturn(Uni.createFrom().item(b));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString())).thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(req, "t1").await().indefinitely();

        verify(coaService).produceAccountingResponseEvent(any(), any(), eq("user2"));
    }

    // 4. Coverage for: handleNoValidBalance (New User)
    @Test
    void testNewUser_NoValidBalance_Disconnect() {
        AccountingRequestDto req = createReq("user3", "s3");
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketUser("user3");

        when(utilCache.getUserData("user3")).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName("user3")).thenReturn(Uni.createFrom().item(List.of(bucket)));
        when(accountingUtil.findBalanceWithHighestPriority(any(), any())).thenReturn(Uni.createFrom().nullItem());
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString())).thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(req, "t1").await().indefinitely();

        verify(coaService).produceAccountingResponseEvent(any(), any(), eq("user3"));
    }

    // 5. Coverage for: handleExistingUserSession -> processSessionWithHighestPriority -> null check


    // 6. Coverage for: updateExistingGroupData (highest priority group balance branch)
    @Test
    void testNewUser_UpdateExistingGroupData() {
        AccountingRequestDto req = createReq("user1", "s1");
        ServiceBucketInfo b = new ServiceBucketInfo();
        b.setBucketUser("groupOwner");
        b.setCurrentBalance(100L);

        Balance groupBalance = new Balance();
        groupBalance.setBucketUsername("groupOwner");

        UserSessionData existingGroupData = new UserSessionData();
        existingGroupData.setSessions(null); // Force the if (existingData.getSessions() == null) line

        when(utilCache.getUserData("user1")).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName("user1")).thenReturn(Uni.createFrom().item(List.of(b)));
        when(accountingUtil.findBalanceWithHighestPriority(any(), any())).thenReturn(Uni.createFrom().item(groupBalance));
        when(utilCache.storeUserData(eq("user1"), any(), anyString())).thenReturn(Uni.createFrom().voidItem());
        when(utilCache.getUserData("groupOwner")).thenReturn(Uni.createFrom().item(existingGroupData));
        when(utilCache.updateUserAndRelatedCaches(eq("groupOwner"), any(), anyString())).thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any())).thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(req, "t1").await().indefinitely();

        assertNotNull(existingGroupData.getSessions());
        verify(utilCache).updateUserAndRelatedCaches(eq("groupOwner"), any(), eq("user1"));
    }


    // 8. Coverage for generic failure branch in processAccountingStart
    @Test
    void testGeneralErrorRecovery() {
        AccountingRequestDto req = createReq("user1", "s1");
        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().failure(new RuntimeException("Redis down")));

        startHandler.processAccountingStart(req, "t1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();
    }
}