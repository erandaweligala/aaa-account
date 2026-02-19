package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.*;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StopHandlerTest {

    @InjectMocks
    StopHandler stopHandler;

    @Mock
    AbstractAccountingHandler accountingHandler;

    @Mock
    CacheClient cacheUtil;

    @Mock
    AccountProducer accountProducer;

    @Mock
    AccountingUtil accountingUtil;

    @Mock
    SessionLifecycleManager sessionLifecycleManager;

    private AccountingRequestDto requestDto;
    private UserSessionData userSessionData;

    @BeforeEach
    void setup() {
        requestDto = new  AccountingRequestDto(
                "evt-1", "ess-123", "127.0.0.1", "testUser",
                AccountingRequestDto.ActionType.STOP, 0, 0, 0,
                Instant.now(), "port-1", "10.0.0.1", 0, 0, 0, "nas-1"
        );
        userSessionData = new UserSessionData();
        userSessionData.setSessions(new ArrayList<>());
        userSessionData.setGroupId("group-1");
    }


    @Test
    void testStopProcessing_ExistingUser_Success() {
        // 1. Setup Cache Mock
        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userSessionData));

        // 2. Setup Session Finding
        Session mockSession = new Session("sess-123", null, null, null, 0, 0L, null, null, null, 0, 0, null, "testUser", null, null, null, 0);
        when(accountingHandler.findSessionById(anyList(), anyString())).thenReturn(mockSession);

        // 3. FIX: Populate the Balance object to avoid NullPointerException
        Balance mockBalance = new Balance();
        mockBalance.setInitialBalance(0L); // Or any appropriate value
        mockBalance.setQuota(100L);
        mockBalance.setServiceId("1234");
        mockBalance.setBucketId("1234");
        // Add any other fields required by MappingUtil.createDBWriteRequest

        UpdateResult successResult = UpdateResult.success(
                100L,
                "buck-1",
                mockBalance,    // Pass the populated balance here
                "prev-buck-1",
                mockSession
        );

        // 4. Setup the rest of the mocks
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), any())).thenReturn(Uni.createFrom().item(successResult));
        when(accountProducer.produceDBWriteEvent(any())).thenReturn(Uni.createFrom().voidItem());
        when(sessionLifecycleManager.onSessionTerminated(anyString(), anyString())).thenReturn(Uni.createFrom().voidItem());

        // 5. Execute
        stopHandler.stopProcessing(requestDto, "buck-1", "trace-1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // 6. Verify (This should now work!)
        verify(accountProducer, times(1)).produceDBWriteEvent(any());
    }
    @Test
    void testStopProcessing_NewUser_TriggersHandleNewSession() {
        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().nullItem());
        when(accountingHandler.handleNewSessionUsage(any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());

        stopHandler.stopProcessing(requestDto, "buck-1", "trace-1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingHandler).handleNewSessionUsage(eq(requestDto), eq("trace-1"), any(), any());
    }

    @Test
    void testProcessAccountingStop_DelayTimeGreaterThanZero() {
        // Create request with delay > 0
        AccountingRequestDto delayedRequest = new AccountingRequestDto(
                "evt-1", "sess-123", "127.0.0.1", "testUser",
                AccountingRequestDto.ActionType.START, 0, 0, 0,
                Instant.now(), "port-1", "10.0.0.1", 5, 0, 0, "nas-1"
        );
        stopHandler.processAccountingStop(userSessionData, delayedRequest, "buck-1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Should return early and not call updateSessionAndBalance
        verify(accountingUtil, never()).updateSessionAndBalance(any(), any(), any(), any());
    }

    @Test
    void testProcessAccountingStop_SessionNotFound_CreatesNew() {
        // ... (keep your Balance initialization from the previous fix) ...

        // Execute
        stopHandler.processAccountingStop(userSessionData, requestDto, "buck-1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // FIX: Change "sess-123" to "ess-123" to match your requestDto
        verify(accountingUtil).updateSessionAndBalance(
                any(),
                argThat(s -> s != null && "ess-123".equals(s.getSessionId())),
                any(),
                any()
        );
    }


    @Test
    void testProcessAccountingStop_UpdateFailed() {



        when(accountingHandler.findSessionById(anyList(), anyString())).thenReturn(new Session());
        // Return failure result
        UpdateResult failResult = new UpdateResult(false, null, null,null,null,null,null);
        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), any())).thenReturn(Uni.createFrom().item(failResult));
        when(sessionLifecycleManager.onSessionTerminated(anyString(), anyString())).thenReturn(Uni.createFrom().voidItem());

        stopHandler.processAccountingStop(userSessionData, requestDto, "buck-1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Should NOT produce DB event if success is false
        verify(accountProducer, never()).produceDBWriteEvent(any());
    }

    @Test
    void testStopProcessing_CacheError_Recovers() {
        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().failure(new RuntimeException("Cache Down")));

        stopHandler.stopProcessing(requestDto, "buck-1", "trace-1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted(); // verify it recovers to Void
    }


}