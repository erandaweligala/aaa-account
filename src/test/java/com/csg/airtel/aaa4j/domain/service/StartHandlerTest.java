package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
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
// todo pls fixed errors
@ExtendWith(MockitoExtension.class)
class StartHandlerTest {

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
    void testProcessAccountingStart_NewUser() {
        AccountingRequestDto request = createRequest();
        List<ServiceBucketInfo> buckets = createBuckets();

        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(buckets));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(createBalance()));
        when(utilCache.storeUserData(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(utilCache).getUserData(anyString());
        verify(userRepository).getServiceBucketsByUserName(anyString());
    }

    @Test
    void testProcessAccountingStart_ExistingUser() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();

        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.findBalanceWithHighestPriority(anyList(), isNull()))
            .thenReturn(Uni.createFrom().item(createBalance()));
        when(utilCache.updateUserAndRelatedCaches(anyString(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionCreated(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(utilCache).getUserData(anyString());
    }

    @Test
    void testProcessAccountingStart_SessionAlreadyExists() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        userData.getSessions().add(createExistingSession(request.sessionId()));

        when(utilCache.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));

        startHandler.processAccountingStart(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(utilCache, never()).updateUserAndRelatedCaches(anyString(), any(), anyString());
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
        return balance;
    }

    private List<ServiceBucketInfo> createBuckets() {
        List<ServiceBucketInfo> buckets = new ArrayList<>();
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setServiceId(1L);
        bucket.setBucketId(100L);
        bucket.setBucketUser("testuser");
        bucket.setCurrentBalance(1000L);
        bucket.setInitialBalance(2000L);
        bucket.setServiceStartDate(LocalDateTime.now());
        bucket.setExpiryDate(LocalDateTime.now().plusDays(30));
        bucket.setStatus("Active");
        bucket.setPriority(1L);
        bucket.setConcurrency(2L);
        bucket.setNotificationTemplates(1L);
        bucket.setUserStatus("ACTIVE");
        bucket.setSessionTimeout("3600");
        buckets.add(bucket);
        return buckets;
    }

    private com.csg.airtel.aaa4j.domain.model.session.Session createExistingSession(String sessionId) {
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
}
