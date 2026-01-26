package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
// todo pls fixed errors
@ExtendWith(MockitoExtension.class)
class AccountingHandlerFactoryTest {

    @Mock
    private StartHandler startHandler;

    @Mock
    private InterimHandler interimHandler;

    @Mock
    private StopHandler stopHandler;

    @InjectMocks
    private AccountingHandlerFactory accountingHandlerFactory;

    private String testTraceId;

    @BeforeEach
    void setUp() {
        testTraceId = "trace-123";
    }

    @Test
    void testGetHandler_StartAction() {
        AccountingRequestDto request = createRequest(AccountingRequestDto.ActionType.START);
        when(startHandler.processAccountingStart(any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        accountingHandlerFactory.getHandler(request, testTraceId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(startHandler).processAccountingStart(eq(request), eq(testTraceId));
        verify(interimHandler, never()).handleInterim(any(), any());
        verify(stopHandler, never()).stopProcessing(any(), any(), any());
    }

    @Test
    void testGetHandler_InterimUpdateAction() {
        AccountingRequestDto request = createRequest(AccountingRequestDto.ActionType.INTERIM_UPDATE);
        when(interimHandler.handleInterim(any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        accountingHandlerFactory.getHandler(request, testTraceId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(interimHandler).handleInterim(eq(request), eq(testTraceId));
        verify(startHandler, never()).processAccountingStart(any(), any());
        verify(stopHandler, never()).stopProcessing(any(), any(), any());
    }

    @Test
    void testGetHandler_StopAction() {
        AccountingRequestDto request = createRequest(AccountingRequestDto.ActionType.STOP);
        when(stopHandler.stopProcessing(any(), any(), any()))
            .thenReturn(Uni.createFrom().voidItem());

        accountingHandlerFactory.getHandler(request, testTraceId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(stopHandler).stopProcessing(eq(request), isNull(), eq(testTraceId));
        verify(startHandler, never()).processAccountingStart(any(), any());
        verify(interimHandler, never()).handleInterim(any(), any());
    }

    private AccountingRequestDto createRequest(AccountingRequestDto.ActionType actionType) {
        return new AccountingRequestDto( //not assign in curect values
            "testuser",
            "session-123",
            actionType,
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
}
