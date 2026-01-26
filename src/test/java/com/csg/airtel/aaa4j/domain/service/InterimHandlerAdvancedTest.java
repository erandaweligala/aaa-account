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

//pls fixed lot off errors pls fixed
@ExtendWith(MockitoExtension.class)
class InterimHandlerAdvancedTest {

    @Mock
    private AbstractAccountingHandler accountingHandler;

    @Mock
    private CacheClient cacheUtil;

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private COAService coaService;

    @Mock
    private AccountingUtil accountingUtil;

    @Mock
    private SessionLifecycleManager sessionLifecycleManager;

    private InterimHandler interimHandler;

    @BeforeEach
    void setUp() {
        interimHandler = new InterimHandler(
            accountingHandler, cacheUtil, accountProducer,
            coaService, accountingUtil, sessionLifecycleManager
        );
    }

    @Test
    void testHandleInterim_CircuitBreakerOpen() {
        AccountingRequestDto request = createRequest();
        when(cacheUtil.getUserData(anyString()))
            .thenReturn(Uni.createFrom().failure(new CircuitBreakerOpenException("Circuit breaker open")));

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheUtil).getUserData(anyString());
    }

    @Test
    void testHandleInterim_UpdateFailed() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
            .thenReturn(Uni.createFrom().item(new UpdateResult(false, 0L, null, null, null)));
        when(sessionLifecycleManager.onSessionActivity(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingUtil).updateSessionAndBalance(any(), any(), any(), isNull());
    }

    @Test
    void testHandleInterim_SessionTimeUnchanged() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();

        // Set session time equal to request time
        Session session = userData.getSessions().get(0);
        session.setSessionTime(request.sessionTime());

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingUtil, never()).updateSessionAndBalance(any(), any(), any(), any());
    }

    @Test
    void testHandleInterim_NoSessionInUserData_WithCachedGroupData() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        userData.setSessions(new ArrayList<>()); // No sessions

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingHandler.handleNewSessionUsage(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingHandler).handleNewSessionUsage(any(), anyString(), any(), any());
    }

    @Test
    void testHandleInterim_SuccessfulUpdate() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
            .thenReturn(Uni.createFrom().item(new UpdateResult(true, 1000L, "bucket-1", null, null)));
        when(sessionLifecycleManager.onSessionActivity(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingUtil).updateSessionAndBalance(any(), any(), any(), isNull());
        verify(sessionLifecycleManager).onSessionActivity(anyString(), anyString());
    }

    @Test
    void testHandleInterim_GenericError() {
        AccountingRequestDto request = createRequest();
        when(cacheUtil.getUserData(anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Database error")));

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheUtil).getUserData(anyString());
    }

    @Test
    void testHandleInterim_SessionCreatedFromCachedData() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        userData.setSessions(new ArrayList<>());
        userData.setGroupId("group-123");

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), isNull()))
            .thenReturn(Uni.createFrom().item(new UpdateResult(true, 1000L, "bucket-1", null, null)));
        when(sessionLifecycleManager.onSessionActivity(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();
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

    private UserSessionData createUserData() {
        List<Balance> balances = new ArrayList<>();
        Balance balance = new Balance();
        balance.setBucketId("bucket-1");
        balance.setQuota(1000L);
        balances.add(balance);

        List<Session> sessions = new ArrayList<>();
        Session session = new Session(
            "session-123",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            500, // Lower than request time
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
        sessions.add(session);

        return UserSessionData.builder()
            .userName("testuser")
            .balance(balances)
            .sessions(sessions)
            .userStatus("ACTIVE")
            .concurrency(2L)
            .sessionTimeOut("3600")
            .build();
    }
}
