package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterimHandlerTest {

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
    void testHandleInterim_NoUserData() {
        AccountingRequestDto request = createRequest();
        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(accountingHandler.handleNewSessionUsage(any(), any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheUtil).getUserData(anyString());
        verify(accountingHandler).handleNewSessionUsage(any(), any(), any(), any());
    }

    @Test
    void testHandleInterim_WithUserData() {
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

        verify(cacheUtil).getUserData(anyString());
    }

    @Test
    void testHandleInterim_UserStatusBarred() {
        AccountingRequestDto request = createRequest();
        UserSessionData userData = createUserData();
        userData.setUserStatus("BARRED");

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));

        interimHandler.handleInterim(request, "trace-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingUtil, never()).updateSessionAndBalance(any(), any(), any(), any());
    }

    @Test
    void testParseLongFast() {
        String input = "0,1234,ACTIVE,3600";
        long result = InterimHandler.parseLongFast(input, 2, 6);

        assertEquals(1234L, result);
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
