package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.UpdateResult;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StopHandlerTest {

    @Mock
    private CacheClient cacheUtil;

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private AccountingUtil accountingUtil;

    private StopHandler stopHandler;

    @BeforeEach
    void setUp() {
        stopHandler = new StopHandler(cacheUtil, accountProducer, accountingUtil);
    }

//    @Test
//    void testStopProcessingWithValidSession() {
//        AccountingRequestDto request = createAccountingRequest();
//        UserSessionData userData = createUserSessionData();
//        String traceId = "test-trace-id";
//        String bucketId = "BUCKET-1";
//
//        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().item(userData));
//        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), eq(bucketId)))
//                .thenReturn(Uni.createFrom().item(UpdateResult.success(5000L, bucketId, createBalance(), null)));
//        when(accountProducer.produceDBWriteEvent(any(DBWriteRequest.class)))
//                .thenReturn(Uni.createFrom().voidItem());
//        when(cacheUtil.updateUserAndRelatedCaches(eq(request.username()), any(UserSessionData.class)))
//                .thenReturn(Uni.createFrom().voidItem());
//        when(accountProducer.produceAccountingCDREvent(any()))
//                .thenReturn(Uni.createFrom().voidItem());
//
//        stopHandler.stopProcessing(request, bucketId, traceId)
//                .subscribe().withSubscriber(UniAssertSubscriber.create())
//                .awaitItem()
//                .assertCompleted();
//
//        verify(cacheUtil, times(1)).getUserData(request.username());
//        verify(accountingUtil, times(1)).updateSessionAndBalance(any(), any(), eq(request), eq(bucketId));
//        verify(accountProducer, times(1)).produceDBWriteEvent(any(DBWriteRequest.class));
//        verify(cacheUtil, times(1)).updateUserAndRelatedCaches(eq(request.username()), any(UserSessionData.class));
//    }

    @Test
    void testStopProcessingWithNullUserData() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";

        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().nullItem());

        stopHandler.stopProcessing(request, null, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(cacheUtil, times(1)).getUserData(request.username());
        verify(accountingUtil, never()).updateSessionAndBalance(any(), any(), any(), any());
    }

//    @Test
//    void testStopProcessingWithNoActiveSessions() {
//        AccountingRequestDto request = createAccountingRequest();
//        UserSessionData userData = new UserSessionData();
//        userData.setUserName("test-user");
//        userData.setSessions(new ArrayList<>());
//        String traceId = "test-trace-id";
//
//        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().item(userData));
//
//        stopHandler.stopProcessing(request, null, traceId)
//                .subscribe().withSubscriber(UniAssertSubscriber.create())
//                .awaitItem()
//                .assertCompleted();
//
//        verify(cacheUtil, times(1)).getUserData(request.username());
//        verify(accountingUtil, never()).updateSessionAndBalance(any(), any(), any(), any());
//    }
//
//    @Test
//    void testStopProcessingWithSessionNotFound() {
//        AccountingRequestDto request = createAccountingRequest();
//        UserSessionData userData = createUserSessionDataWithDifferentSession();
//        String traceId = "test-trace-id";
//
//        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().item(userData));
//
//        stopHandler.stopProcessing(request, null, traceId)
//                .subscribe().withSubscriber(UniAssertSubscriber.create())
//                .awaitItem()
//                .assertCompleted();
//
//        verify(cacheUtil, times(1)).getUserData(request.username());
//        verify(accountingUtil, never()).updateSessionAndBalance(any(), any(), any(), any());
//    }
//
//
//    @Test
//    void testStopProcessingWithCacheUpdateFailure() {
//        AccountingRequestDto request = createAccountingRequest();
//        UserSessionData userData = createUserSessionData();
//        String traceId = "test-trace-id";
//
//        when(cacheUtil.getUserData(request.username())).thenReturn(Uni.createFrom().item(userData));
//        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
//                .thenReturn(Uni.createFrom().item(UpdateResult.success(5000L, "BUCKET-1", createBalance(), null)));
//        when(accountProducer.produceDBWriteEvent(any(DBWriteRequest.class)))
//                .thenReturn(Uni.createFrom().voidItem());
//        when(cacheUtil.updateUserAndRelatedCaches(eq(request.username()), any(UserSessionData.class)))
//                .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache update failed")));
//        when(accountProducer.produceAccountingCDREvent(any()))
//                .thenReturn(Uni.createFrom().voidItem());
//
//        stopHandler.stopProcessing(request, null, traceId)
//                .subscribe().withSubscriber(UniAssertSubscriber.create())
//                .awaitItem()
//                .assertCompleted();
//
//        verify(cacheUtil, times(1)).updateUserAndRelatedCaches(eq(request.username()), any(UserSessionData.class));
//    }

    @Test
    void testStopProcessingWithGeneralFailure() {
        AccountingRequestDto request = createAccountingRequest();
        String traceId = "test-trace-id";

        when(cacheUtil.getUserData(request.username()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));

        stopHandler.stopProcessing(request, null, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(cacheUtil, times(1)).getUserData(request.username());
    }

    private AccountingRequestDto createAccountingRequest() {
        return new AccountingRequestDto(
                "event-id-123",
                "session-id-123",
                "10.0.0.1",
                "test-user",
                AccountingRequestDto.ActionType.STOP,
                3000,
                4000,
                200,
                Instant.now(),
                "NAS-PORT-1",
                "192.168.1.1",
                0,
                3,
                4,
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
                100,
                2000L,
                "192.168.1.1",
                "10.0.0.1"
        );
        userData.setSessions(new ArrayList<>(List.of(session)));

        return userData;
    }

    private UserSessionData createUserSessionDataWithDifferentSession() {
        UserSessionData userData = new UserSessionData();
        userData.setUserName("test-user");
        userData.setBalance(List.of(createBalance()));

        Session session = new Session(
                "different-session-id",
                LocalDateTime.now(),
                null,
                100,
                2000L,
                "192.168.1.1",
                "10.0.0.1"
        );
        userData.setSessions(new ArrayList<>(List.of(session)));

        return userData;
    }

    private Balance createBalance() {
        Balance balance = new Balance();
        balance.setBucketId("BUCKET-1");
        balance.setServiceId("");
        balance.setQuota(5000L);
        balance.setInitialBalance(10000L);
        balance.setPriority(1L);
        balance.setServiceStatus("Active");
        balance.setTimeWindow("0-24");
        return balance;
    }
}
