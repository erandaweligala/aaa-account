package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto.ActionType;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    StartHandler startHandler;
    @Mock
    InterimHandler interimHandler;
    @Mock
    StopHandler stopHandler;

    private AccountingHandlerFactory factory;
    private final String traceId = "trace-uuid-12345";

    @BeforeEach
    void setUp() {
        factory = new AccountingHandlerFactory(startHandler, interimHandler, stopHandler);
    }

    @Test
    @DisplayName("Should route to StartHandler when actionType is START")
    void testGetHandler_Start() {
        AccountingRequestDto request = createRequest(ActionType.START);
        when(startHandler.processAccountingStart(eq(request), eq(traceId)))
                .thenReturn(Uni.createFrom().voidItem());

        factory.getHandler(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(startHandler).processAccountingStart(request, traceId);
        verifyNoInteractions(interimHandler, stopHandler);
    }

    @Test
    @DisplayName("Should route to InterimHandler when actionType is INTERIM_UPDATE")
    void testGetHandler_Interim() {
        AccountingRequestDto request = createRequest(ActionType.INTERIM_UPDATE);
        when(interimHandler.handleInterim(eq(request), eq(traceId)))
                .thenReturn(Uni.createFrom().voidItem());

        factory.getHandler(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(interimHandler).handleInterim(request, traceId);
        verifyNoInteractions(startHandler, stopHandler);
    }

    @Test
    @DisplayName("Should route to StopHandler when actionType is STOP")
    void testGetHandler_Stop() {
        AccountingRequestDto request = createRequest(ActionType.STOP);
        when(stopHandler.stopProcessing(eq(request), any(), eq(traceId)))
                .thenReturn(Uni.createFrom().voidItem());

        factory.getHandler(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Note: passing null as the second argument as per your factory implementation
        verify(stopHandler).stopProcessing(request, null, traceId);
        verifyNoInteractions(startHandler, interimHandler);
    }

    /**
     * Helper to create the DTO record with required fields.
     */
    private AccountingRequestDto createRequest(ActionType actionType) {
        return new AccountingRequestDto(
                "event123",
                "sess-abc",
                "127.0.0.1",
                "test_user",
                actionType,
                100, 200, 3600,
                Instant.now(),
                "port1",
                "10.0.0.1",
                0, 0, 0,
                "nas-id-01"
        );
    }
}