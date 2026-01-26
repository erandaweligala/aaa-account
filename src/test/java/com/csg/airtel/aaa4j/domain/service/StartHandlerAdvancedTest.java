package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.repository.UserBucketRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartHandlerAdvancedTest {

    @Mock
    private CacheClient utilCache;

    @Mock
    private UserBucketRepository userRepository;

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private AccountingUtil accountingUtil;

    @Mock
    private SessionLifecycleManager sessionLifecycleManager;

    @Mock
    private COAService coaService;

    @Mock
    private AbstractAccountingHandler accountingHandler;

    private StartHandler startHandler;

    @BeforeEach
    void setUp() {
        startHandler = new StartHandler(
            utilCache, userRepository, accountProducer, accountingUtil,
            sessionLifecycleManager, coaService, accountingHandler
        );
    }

    @Test
    void testProcessAccountingStart_ExistingUser_WithGroupBalance() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserDataWithGroup();

        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(utilCache.getUserData("group-123")).thenReturn(Uni.createFrom().item(createGroupData()));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(createGroupBalance()));
        when(utilCache.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(utilCache, atLeast(1)).getUserData(anyString());
    }

    @Test
    void testProcessAccountingStart_NoBalance() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();

        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().nullItem());
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(coaService).produceAccountingResponseEvent(any(), any(), anyString());
    }

    @Test
    void testProcessAccountingStart_ZeroQuota() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();

        Balance balance = createBalance();
        balance.setQuota(0L);
        balance.setUnlimited(false);

        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(balance));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(coaService).produceAccountingResponseEvent(any(), any(), anyString());
    }

    @Test
    void testProcessAccountingStart_NewUser_WithGroupBuckets() {
        AccountingRequestDto request = createRequest();
        List<ServiceBucketInfo> buckets = createGroupBuckets();

        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(buckets));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(createGroupBalance()));
        when(utilCache.storeUserData(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(utilCache.getUserData("groupuser"))
            .thenReturn(Uni.createFrom().item(createGroupData()));
        when(sessionLifecycleManager.onSessionCreated(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(userRepository).getServiceBucketsByUserName(anyString());
    }

    @Test
    void testProcessAccountingStart_ConcurrencyExceeded_Individual() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        userData.setConcurrency(1L);

        Session existingSession = createExistingSession("session-999");
        existingSession.setNasPortId("port-different");
        userData.getSessions().add(existingSession);

        Balance balance = createBalance();
        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(balance));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(utilCache.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();
    }

    @Test
    void testProcessAccountingStart_GroupConcurrencyExceeded() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserDataWithGroup();

        when(utilCache.getUserData("testuser")).thenReturn(Uni.createFrom().item(userData));

        UserSessionData groupData = createGroupData();
        Session existingSession = createExistingSession("session-999");
        existingSession.setUserName("testuser");
        existingSession.setNasPortId("port-different");
        existingSession.setUserConcurrency(1L);
        groupData.getSessions().add(existingSession);

        when(utilCache.getUserData("group-123")).thenReturn(Uni.createFrom().item(groupData));

        Balance groupBalance = createGroupBalance();
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(groupBalance));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(coaService).produceAccountingResponseEvent(any(), any(), anyString());
    }

    @Test
    void testProcessAccountingStart_NoServiceBuckets() {
        AccountingRequestDto request = createRequest();

        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(new ArrayList<>()));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(coaService).produceAccountingResponseEvent(any(), any(), anyString());
    }

    @Test
    void testProcessAccountingStart_StoreGroupDataNew() {
        AccountingRequestDto request = createRequest();
        List<ServiceBucketInfo> buckets = createGroupBuckets();

        when(utilCache.getUserData("testuser")).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(buckets));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(createGroupBalance()));
        when(utilCache.storeUserData(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(utilCache.getUserData("groupuser")).thenReturn(Uni.createFrom().nullItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(utilCache, atLeast(2)).storeUserData(anyString(), any(), anyString());
    }

    @Test
    void testProcessAccountingStart_StoreGroupDataExisting() {
        AccountingRequestDto request = createRequest();
        List<ServiceBucketInfo> buckets = createGroupBuckets();

        when(utilCache.getUserData("testuser")).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(buckets));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(createGroupBalance()));
        when(utilCache.storeUserData(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(utilCache.getUserData("groupuser"))
            .thenReturn(Uni.createFrom().item(createGroupData()));
        when(utilCache.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(utilCache).updateUserAndRelatedCaches(eq("groupuser"), any(), anyString());
    }

    @Test
    void testProcessAccountingStart_UpdateBothCaches() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserDataWithGroup();

        when(utilCache.getUserData("testuser")).thenReturn(Uni.createFrom().item(userData));

        UserSessionData groupData = createGroupData();
        when(utilCache.getUserData("group-123")).thenReturn(Uni.createFrom().item(groupData));

        Balance groupBalance = createGroupBalance();
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(groupBalance));
        when(utilCache.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(utilCache, atLeast(2)).updateUserAndRelatedCaches(anyString(), any(), anyString());
    }

    private AccountingRequestDto createRequest() {
        return new AccountingRequestDto(
            "testuser",
            "session-123",
            AccountingRequestDto.ActionType.START,
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

    private UserSessionData createUserData() {
        List<Balance> balances = new ArrayList<>();
        balances.add(createBalance());

        return UserSessionData.builder()
            .userName("testuser")
            .balance(balances)
            .sessions(new ArrayList<>())
            .concurrency(2L)
            .groupId("1")
            .build();
    }

    private UserSessionData createUserDataWithGroup() {
        List<Balance> balances = new ArrayList<>();
        balances.add(createBalance());

        return UserSessionData.builder()
            .userName("testuser")
            .balance(balances)
            .sessions(new ArrayList<>())
            .concurrency(2L)
            .groupId("group-123")
            .build();
    }

    private UserSessionData createGroupData() {
        List<Balance> balances = new ArrayList<>();
        balances.add(createGroupBalance());

        return UserSessionData.builder()
            .userName("groupuser")
            .balance(balances)
            .sessions(new ArrayList<>())
            .concurrency(5L)
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
        balance.setServiceStartDate(LocalDateTime.now());
        balance.setServiceStatus("Active");
        balance.setPriority(1L);
        balance.setBucketUsername("testuser");
        balance.setGroup(false);
        return balance;
    }

    private Balance createGroupBalance() {
        Balance balance = new Balance();
        balance.setBucketId("group-bucket-1");
        balance.setServiceId("service-1");
        balance.setQuota(5000L);
        balance.setInitialBalance(10000L);
        balance.setServiceExpiry(LocalDateTime.now().plusDays(30));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        balance.setServiceStartDate(LocalDateTime.now());
        balance.setServiceStatus("Active");
        balance.setPriority(1L);
        balance.setBucketUsername("groupuser");
        balance.setGroup(true);
        return balance;
    }

    private List<ServiceBucketInfo> createGroupBuckets() {
        List<ServiceBucketInfo> buckets = new ArrayList<>();

        ServiceBucketInfo bucket1 = new ServiceBucketInfo();
        bucket1.setServiceId(1L);
        bucket1.setBucketId(100L);
        bucket1.setBucketUser("testuser");
        bucket1.setCurrentBalance(1000L);
        bucket1.setInitialBalance(2000L);
        bucket1.setServiceStartDate(LocalDateTime.now());
        bucket1.setExpiryDate(LocalDateTime.now().plusDays(30));
        bucket1.setStatus("Active");
        bucket1.setPriority(2L);
        bucket1.setConcurrency(2L);
        bucket1.setNotificationTemplates(1L);
        bucket1.setUserStatus("ACTIVE");
        bucket1.setSessionTimeout("3600");
        bucket1.setGroup(false);
        buckets.add(bucket1);

        ServiceBucketInfo bucket2 = new ServiceBucketInfo();
        bucket2.setServiceId(2L);
        bucket2.setBucketId(200L);
        bucket2.setBucketUser("groupuser");
        bucket2.setCurrentBalance(5000L);
        bucket2.setInitialBalance(10000L);
        bucket2.setServiceStartDate(LocalDateTime.now());
        bucket2.setExpiryDate(LocalDateTime.now().plusDays(30));
        bucket2.setStatus("Active");
        bucket2.setPriority(1L);
        bucket2.setConcurrency(5L);
        bucket2.setNotificationTemplates(1L);
        bucket2.setUserStatus("ACTIVE");
        bucket2.setSessionTimeout("3600");
        bucket2.setGroup(true);
        buckets.add(bucket2);

        return buckets;
    }

    private Session createExistingSession(String sessionId) {
        return new Session(
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
            2L
        );
    }
}
