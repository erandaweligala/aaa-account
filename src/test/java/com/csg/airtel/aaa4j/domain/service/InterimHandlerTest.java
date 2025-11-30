package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.UpdateResult;
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
class InterimHandlerTest {

    @Mock
    private CacheClient cacheUtil;

    @Mock
    private UserBucketRepository userRepository;

    @Mock
    private AccountingUtil accountingUtil;

    @Mock
    private AccountProducer accountProducer;

    private InterimHandler interimHandler;

    @BeforeEach
    void setUp() {
        interimHandler = new InterimHandler(cacheUtil, userRepository, accountingUtil, accountProducer);
    }

    @Test
    void testHandleInterimWithExistingUser() {
        AccountingRequestDto request = createAccountingRequest(100);
        UserSessionData userData = createUserSessionData();
        String traceId = "test-trace-id";

        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
                .thenReturn(Uni.createFrom().item(UpdateResult.success(5000L, "BUCKET-1", createBalance(), null)));
        when(accountProducer.produceAccountingCDREvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(cacheUtil, times(1)).getUserData(request.username());
        verify(accountingUtil, times(1)).updateSessionAndBalance(any(), any(), eq(request), isNull());
    }

    @Test
    void testHandleInterimWithNewSession() {
        AccountingRequestDto request = createAccountingRequest(100);
        String traceId = "test-trace-id";
        List<ServiceBucketInfo> buckets = createServiceBuckets();

        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(request.username()))
                .thenReturn(Uni.createFrom().item(buckets));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
                .thenReturn(Uni.createFrom().item(UpdateResult.success(5000L, "BUCKET-1", createBalance(), null)));
        when(accountProducer.produceAccountingCDREvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(cacheUtil, times(1)).getUserData(request.username());
        verify(userRepository, times(1)).getServiceBucketsByUserName(request.username());
    }

    @Test
    void testHandleInterimWithNoServiceBuckets() {
        AccountingRequestDto request = createAccountingRequest(100);
        String traceId = "test-trace-id";

        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(request.username()))
                .thenReturn(Uni.createFrom().item(new ArrayList<>()));
        when(accountProducer.produceAccountingResponseEvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountProducer, times(1)).produceAccountingResponseEvent(
                argThat(event -> event.action() == AccountingResponseEvent.ResponseAction.DISCONNECT)
        );
    }

    @Test
    void testHandleInterimWithZeroQuota() {
        AccountingRequestDto request = createAccountingRequest(100);
        String traceId = "test-trace-id";
        List<ServiceBucketInfo> buckets = createServiceBucketsWithZeroBalance();

        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().nullItem());
        when(userRepository.getServiceBucketsByUserName(request.username()))
                .thenReturn(Uni.createFrom().item(buckets));
        when(accountProducer.produceAccountingResponseEvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountProducer, times(1)).produceAccountingResponseEvent(
                argThat(event -> event.action() == AccountingResponseEvent.ResponseAction.DISCONNECT)
        );
    }

    @Test
    void testHandleInterimWithDuplicateSessionTime() {
        AccountingRequestDto request = createAccountingRequest(50);
        UserSessionData userData = createUserSessionData();
        String traceId = "test-trace-id";

        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().item(userData));

        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingUtil, never()).updateSessionAndBalance(any(), any(), any(), any());
    }

    @Test
    void testHandleInterimWithFailure() {
        AccountingRequestDto request = createAccountingRequest(100);
        String traceId = "test-trace-id";

        when(cacheUtil.getUserData(request.username()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));

        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(cacheUtil, times(1)).getUserData(request.username());
    }

    @Test
    void testHandleInterimWithUpdateFailure() {
        AccountingRequestDto request = createAccountingRequest(100);
        UserSessionData userData = createUserSessionData();
        String traceId = "test-trace-id";

        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
                .thenReturn(Uni.createFrom().item(UpdateResult.failure("Update failed")));
        when(accountProducer.produceAccountingCDREvent(any()))
                .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingUtil, times(1)).updateSessionAndBalance(any(), any(), eq(request), isNull());
    }

    private AccountingRequestDto createAccountingRequest(int sessionTime) {
        return new AccountingRequestDto(
                "event-id-123",
                "session-id-123",
                "10.0.0.1",
                "test-user",
                AccountingRequestDto.ActionType.INTERIM_UPDATE,
                1000,
                2000,
                sessionTime,
                Instant.now(),
                "NAS-PORT-1",
                "192.168.1.1",
                0,
                1,
                2,
                "NAS-1"
        );
    }

    private UserSessionData createUserSessionData() {
        UserSessionData userData = new UserSessionData();
        userData.setUserName("test-user");
        userData.setBalance(List.of(createBalance()));

        Session session = new Session(
                "session-id-123",
                LocalDateTime.now(),
                null,
                50,
                1000L,
                "192.168.1.1",
                "10.0.0.1"
        );
        userData.setSessions(new ArrayList<>(List.of(session)));

        return userData;
    }

    private Balance createBalance() {
        Balance balance = new Balance();
        balance.setBucketId("BUCKET-1");
        balance.setServiceId(1L);
        balance.setQuota(5000L);
        balance.setInitialBalance(10000L);
        balance.setPriority(1L);
        balance.setServiceStatus("Active");
        balance.setTimeWindow("0-24");
        return balance;
    }

    private List<ServiceBucketInfo> createServiceBuckets() {
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketId("BUCKET-1");
        bucket.setServiceId(1L);
        bucket.setCurrentBalance(5000.0);
        bucket.setInitialBalance(10000.0);
        bucket.setPriority(1L);
        bucket.setServiceStatus("Active");
        bucket.setTimeWindow("0-24");
        bucket.setBucketUser("test-user");
        return List.of(bucket);
    }

    private List<ServiceBucketInfo> createServiceBucketsWithZeroBalance() {
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketId("BUCKET-1");
        bucket.setServiceId(1L);
        bucket.setCurrentBalance(0.0);
        bucket.setInitialBalance(0.0);
        bucket.setPriority(1L);
        bucket.setServiceStatus("Active");
        bucket.setTimeWindow("0-24");
        bucket.setBucketUser("test-user");
        return List.of(bucket);
    }
}
