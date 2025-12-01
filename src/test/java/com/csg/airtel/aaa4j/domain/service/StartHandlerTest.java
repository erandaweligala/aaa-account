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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartHandlerTest {

    @Mock
    private CacheClient utilCache;

    @Mock
    private UserBucketRepository userRepository;

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private  AccountingUtil accountingUtil;

    private StartHandler startHandler;

    @BeforeEach
    void setUp() {
        startHandler = new StartHandler(utilCache, userRepository, accountProducer,accountingUtil);
    }

    @Test
    void testProcessAccountingStartWithNewUser() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";
        List<ServiceBucketInfo> buckets = createServiceBuckets();

        when(utilCache.getUserData(request.username())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(request.username()))
                .thenReturn(Uni.createFrom().item(buckets));
        when(utilCache.storeUserData(eq(request.username()), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceAccountingCDREvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(utilCache, times(1)).getUserData(request.username());
        verify(userRepository, times(1)).getServiceBucketsByUserName(request.username());
        verify(utilCache, times(1)).storeUserData(eq(request.username()), any(UserSessionData.class));
    }

    @Test
    void testProcessAccountingStartWithExistingUserNewSession() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";
        UserSessionData existingUserData = createUserSessionData();

        when(utilCache.getUserData(request.username())).thenReturn(Uni.createFrom().item(existingUserData));
        when(utilCache.updateUserAndRelatedCaches(eq(request.username()), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceAccountingCDREvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(utilCache, times(1)).getUserData(request.username());
        verify(utilCache, times(1)).updateUserAndRelatedCaches(eq(request.username()), any(UserSessionData.class));
    }

    @Test
    void testProcessAccountingStartWithExistingSession() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";
        UserSessionData existingUserData = createUserSessionDataWithSession(request.sessionId());

        when(utilCache.getUserData(request.username())).thenReturn(Uni.createFrom().item(existingUserData));

        startHandler.processAccountingStart(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(utilCache, times(1)).getUserData(request.username());
        verify(utilCache, never()).updateUserAndRelatedCaches(any(), any());
    }

    @Test
    void testProcessAccountingStartWithNoServiceBuckets() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";

        when(utilCache.getUserData(request.username())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(request.username()))
                .thenReturn(Uni.createFrom().item(new ArrayList<>()));
        when(accountProducer.produceAccountingResponseEvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountProducer, times(1)).produceAccountingResponseEvent(
                argThat(event -> event.action() == AccountingResponseEvent.ResponseAction.DISCONNECT)
        );
    }

    @Test
    void testProcessAccountingStartWithZeroQuota() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";
        List<ServiceBucketInfo> bucketsWithZeroBalance = createServiceBucketsWithZeroBalance();

        when(utilCache.getUserData(request.username())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(request.username()))
                .thenReturn(Uni.createFrom().item(bucketsWithZeroBalance));
        when(accountProducer.produceAccountingResponseEvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountProducer, times(1)).produceAccountingResponseEvent(
                argThat(event -> event.action() == AccountingResponseEvent.ResponseAction.DISCONNECT)
        );
    }

    @Test
    void testProcessAccountingStartWithExhaustedBalance() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";
        UserSessionData existingUserData = createUserSessionDataWithZeroBalance();

        when(utilCache.getUserData(request.username())).thenReturn(Uni.createFrom().item(existingUserData));
        when(accountProducer.produceAccountingResponseEvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountProducer, times(1)).produceAccountingResponseEvent(
                argThat(event -> event.action() == AccountingResponseEvent.ResponseAction.DISCONNECT)
        );
    }

    @Test
    @SuppressWarnings("java:S6068")
    void testProcessAccountingStartWithGroupBuckets() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";
        List<ServiceBucketInfo> buckets = createServiceBucketsWithGroup();

        when(utilCache.getUserData(request.username())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(request.username()))
                .thenReturn(Uni.createFrom().item(buckets));
        when(utilCache.storeUserData(eq(request.username()), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());
        when(utilCache.getUserData(eq("group-user"))).thenReturn(Uni.createFrom().nullItem());
        when(utilCache.storeUserData(eq("group-user"), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceAccountingCDREvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        startHandler.processAccountingStart(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(utilCache, times(1)).storeUserData(eq(request.username()), any(UserSessionData.class));
        verify(utilCache, times(1)).storeUserData(eq("group-user"), any(UserSessionData.class));
    }

    @Test
    void testProcessAccountingStartWithFailure() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";

        when(utilCache.getUserData(request.username()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));

        startHandler.processAccountingStart(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(utilCache, times(1)).getUserData(request.username());
    }

    private AccountingRequestDto createAccountingRequest() {
        return new AccountingRequestDto(
                "event-id-123",
                "session-id-123",
                "10.0.0.1",
                "test-user",
                AccountingRequestDto.ActionType.START,
                0,
                0,
                0,
                Instant.now(),
                "NAS-PORT-1",
                "192.168.1.1",
                0,
                0,
                0,
                "NAS-1"
        );
    }

    private UserSessionData createUserSessionData() {
        UserSessionData userData = new UserSessionData();
        userData.setUserName("test-user");
        userData.setBalance(List.of(createBalance()));
        userData.setSessions(new ArrayList<>());
        return userData;
    }

    private UserSessionData createUserSessionDataWithSession(String sessionId) {
        UserSessionData userData = createUserSessionData();
        Session session = new Session(
                sessionId,
                LocalDateTime.now(),
                null,
                0,
                0L,
                "192.168.1.1",
                "10.0.0.1"
        );
        userData.setSessions(new ArrayList<>(List.of(session)));
        return userData;
    }

    private UserSessionData createUserSessionDataWithZeroBalance() {
        UserSessionData userData = new UserSessionData();
        userData.setUserName("test-user");
        Balance balance = createBalance();
        balance.setQuota(0L);
        userData.setBalance(List.of(balance));
        userData.setSessions(new ArrayList<>());
        return userData;
    }

    private Balance createBalance() {
        Balance balance = new Balance();
        balance.setBucketId("BUCKET-1");
        balance.setServiceId("1");
        balance.setQuota(5000L);
        balance.setInitialBalance(10000L);
        balance.setPriority(1L);
        balance.setServiceStatus("Active");
        balance.setTimeWindow("0-24");
        return balance;
    }

    private List<ServiceBucketInfo> createServiceBuckets() {
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketId(1);
        bucket.setServiceId(1L);
        bucket.setCurrentBalance(5000);
        bucket.setInitialBalance(10000);
        bucket.setPriority(1L);
        bucket.setStatus("Active");
        bucket.setTimeWindow("0-24");
        bucket.setBucketUser("test-user");
        return List.of(bucket);
    }

    private List<ServiceBucketInfo> createServiceBucketsWithZeroBalance() {
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketId(1);
        bucket.setServiceId(1L);
        bucket.setCurrentBalance(0);
        bucket.setInitialBalance(0);
        bucket.setPriority(1L);
        bucket.setStatus("Active");
        bucket.setTimeWindow("0-24");
        bucket.setBucketUser("test-user");
        return List.of(bucket);
    }

    private List<ServiceBucketInfo> createServiceBucketsWithGroup() {
        List<ServiceBucketInfo> buckets = new ArrayList<>();

        ServiceBucketInfo userBucket = new ServiceBucketInfo();
        userBucket.setBucketId(1);
        userBucket.setServiceId(1L);
        userBucket.setCurrentBalance(5000);
        userBucket.setInitialBalance(10000);
        userBucket.setPriority(1L);
        userBucket.setStatus("Active");
        userBucket.setTimeWindow("0-24");
        userBucket.setBucketUser("test-user");
        buckets.add(userBucket);

        ServiceBucketInfo groupBucket = new ServiceBucketInfo();
        groupBucket.setBucketId(2);
        groupBucket.setServiceId(2L);
        groupBucket.setCurrentBalance(3000);
        groupBucket.setInitialBalance(5000);
        groupBucket.setPriority(2L);
        groupBucket.setStatus("Active");
        groupBucket.setTimeWindow("0-24");
        groupBucket.setBucketUser("group-user");
        buckets.add(groupBucket);

        return buckets;
    }
}
