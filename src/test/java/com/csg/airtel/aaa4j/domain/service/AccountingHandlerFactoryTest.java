package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.ProcessType;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountingHandlerFactoryTest {

    @Mock
    private StartHandler startHandler;

    @Mock
    private InterimHandler interimHandler;

    @Mock
    private StopHandler stopHandler;

    private AccountingHandlerFactory accountingHandlerFactory;

    @BeforeEach
    void setUp() {
        accountingHandlerFactory = new AccountingHandlerFactory(startHandler, interimHandler, stopHandler);
    }

    @Test
    void testGetHandlerForStartAction() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START);
        String traceId = "test-trace-id";

        when(startHandler.processAccountingStart(eq(request), eq(traceId)))
                .thenReturn(Uni.createFrom().voidItem());

        accountingHandlerFactory.getHandler(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(startHandler, times(1)).processAccountingStart(eq(request), eq(traceId));
        verify(interimHandler, never()).handleInterim(any(), any());
        verify(stopHandler, never()).stopProcessing(any(), any(), any());
    }

    @Test
    void testGetHandlerForInterimUpdateAction() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.INTERIM_UPDATE);
        String traceId = "test-trace-id";

        when(interimHandler.handleInterim(eq(request), eq(traceId)))
                .thenReturn(Uni.createFrom().voidItem());

        accountingHandlerFactory.getHandler(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(interimHandler, times(1)).handleInterim(eq(request), eq(traceId));
        verify(startHandler, never()).processAccountingStart(any(), any());
        verify(stopHandler, never()).stopProcessing(any(), any(), any());
    }

    @Test
    void testGetHandlerForStopAction() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.STOP);
        String traceId = "test-trace-id";

        when(stopHandler.stopProcessing(eq(request), isNull(), eq(traceId)))
                .thenReturn(Uni.createFrom().voidItem());

        accountingHandlerFactory.getHandler(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(stopHandler, times(1)).stopProcessing(eq(request), isNull(), eq(traceId));
        verify(startHandler, never()).processAccountingStart(any(), any());
        verify(interimHandler, never()).handleInterim(any(), any());
    }

    private AccountingRequestDto createAccountingRequest(ProcessType actionType) {
        return new AccountingRequestDto(
                "event-id-123",
                "session-id-123",
                "test-user",
                "192.168.1.1",
                "10.0.0.1",
                "NAS-1",
                "NAS-PORT-1",
                actionType,
                Instant.now(),
                100,
                1000,
                2000,
                1,
                2
        );
    }
}
