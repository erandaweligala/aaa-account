package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
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
class StopHandlerAdvancedTest {

    @Mock
    private CacheClient cacheUtil;

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private AccountingUtil accountingUtil;

    @Mock
    private SessionLifecycleManager sessionLifecycleManager;

    @Mock
    private AbstractAccountingHandler accountingHandler;

    @Mock
    private COAService coaService;

    private StopHandler stopHandler;

    @BeforeEach
    void setUp() {
        stopHandler = new StopHandler(
            cacheUtil, accountProducer, accountingUtil,
            sessionLifecycleManager, accountingHandler, coaService
        );
    }

    @Test
    void testStopProcessing_SuccessfulStop() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        Session session = createSession();

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
            .thenReturn(Uni.createFrom().item(new UpdateResult(true, 500L, "bucket-1", null, null)));
        when(sessionLifecycleManager.onSessionTerminated(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceDBWriteEvent(any())).thenReturn(Uni.createFrom().voidItem());

        stopHandler.stopProcessing(request, session, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingUtil).updateSessionAndBalance(any(), any(), any(), isNull());
        verify(sessionLifecycleManager).onSessionTerminated(anyString(), anyString());
    }

    @Test
    void testStopProcessing_UpdateFailed() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        Session session = createSession();

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
            .thenReturn(Uni.createFrom().item(new UpdateResult(false, 0L, null, null, null)));
        when(sessionLifecycleManager.onSessionTerminated(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        stopHandler.stopProcessing(request, session, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(sessionLifecycleManager).onSessionTerminated(anyString(), anyString());
    }

    @Test
    void testStopProcessing_SessionNotFound() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        userData.setSessions(new ArrayList<>()); // No sessions

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(sessionLifecycleManager.onSessionTerminated(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        stopHandler.stopProcessing(request, null, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(sessionLifecycleManager).onSessionTerminated(anyString(), anyString());
    }

    @Test
    void testStopProcessing_CircuitBreakerOpen() {
        AccountingRequestDto request = createRequest();
        when(cacheUtil.getUserData(anyString()))
            .thenReturn(Uni.createFrom().failure(new CircuitBreakerOpenException("Circuit breaker open")));

        stopHandler.stopProcessing(request, null, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheUtil).getUserData(anyString());
    }

    @Test
    void testStopProcessing_GenericError() {
        AccountingRequestDto request = createRequest();
        when(cacheUtil.getUserData(anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Database error")));

        stopHandler.stopProcessing(request, null, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheUtil).getUserData(anyString());
    }

    @Test
    void testStopProcessing_WithProvidedSession() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        Session providedSession = createSession();
        providedSession.setPreviousUsageBucketId("bucket-1");
        providedSession.setServiceId("service-1");

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
            .thenReturn(Uni.createFrom().item(new UpdateResult(true, 500L, "bucket-1", null, null)));
        when(sessionLifecycleManager.onSessionTerminated(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceDBWriteEvent(any())).thenReturn(Uni.createFrom().voidItem());

        stopHandler.stopProcessing(request, providedSession, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingUtil).updateSessionAndBalance(any(), any(), any(), isNull());
    }

    @Test
    void testStopProcessing_DBWriteEventFailure() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        Session session = createSession();

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
            .thenReturn(Uni.createFrom().item(new UpdateResult(true, 500L, "bucket-1", null, null)));
        when(sessionLifecycleManager.onSessionTerminated(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());
        when(accountProducer.produceDBWriteEvent(any()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Kafka error")));

        stopHandler.stopProcessing(request, session, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountProducer).produceDBWriteEvent(any());
    }

    @Test
    void testStopProcessing_BarredUserWithSession() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        userData.setUserStatus("BARRED");
        Session session = createSession();
        session.setPreviousUsageBucketId("bucket-1");
        session.setServiceId("service-1");

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(sessionLifecycleManager.onSessionTerminated(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        stopHandler.stopProcessing(request, session, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(sessionLifecycleManager).onSessionTerminated(anyString(), anyString());
        verify(accountingUtil, never()).updateSessionAndBalance(any(), any(), any(), any());
    }

    @Test
    void testStopProcessing_NewUserSession() {
        AccountingRequestDto request = createRequest();

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(accountingHandler.handleNewSessionUsage(any(), anyString(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        stopHandler.stopProcessing(request, null, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingHandler).handleNewSessionUsage(any(), anyString(), any(), any());
    }

    private AccountingRequestDto createRequest() {
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

    private UserSessionData createUserData() {
        List<Balance> balances = new ArrayList<>();
        Balance balance = new Balance();
        balance.setBucketId("bucket-1");
        balance.setQuota(1000L);
        balances.add(balance);

        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession());

        return UserSessionData.builder()
            .userName("testuser")
            .balance(balances)
            .sessions(sessions)
            .userStatus("ACTIVE")
            .build();
    }

    private Session createSession() {
        return new Session(
            "session-123",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            1000,
            1000L,
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
