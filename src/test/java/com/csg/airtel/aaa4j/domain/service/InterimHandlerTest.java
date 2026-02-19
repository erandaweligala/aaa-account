package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.UpdateResult;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterimHandlerTest {

    @Mock AbstractAccountingHandler accountingHandler;
    @Mock CacheClient cacheUtil;
    @Mock AccountProducer accountProducer;
    @Mock COAService coaService;
    @Mock AccountingUtil accountingUtil;
    @Mock SessionLifecycleManager sessionLifecycleManager;

    @InjectMocks
    InterimHandler interimHandler;

    private AccountingRequestDto request;
    private final String traceId = "test-trace-id";

    @BeforeEach
    void setUp() {
        request = new AccountingRequestDto(
                "event-123",              // eventId
                "sess-001",               // sessionId
                "10.0.0.1",               // nasIP
                "user123",                // username
                AccountingRequestDto.ActionType.INTERIM_UPDATE, // actionType
                1000,                     // inputOctets
                2000,                     // outputOctets
                100,                      // sessionTime
                java.time.Instant.now(),  // timestamp
                "port-1",                 // nasPortId
                "192.168.1.1",            // framedIPAddress
                0,                        // delayTime
                0,                        // inputGigaWords
                0,                        // outputGigaWords
                "nas-id-01"               // nasIdentifier
        );
    }

    @Test
    void testHandleInterim_NewSession() {
        // Arrange: Cache returns null (new user/session)
        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item((UserSessionData) null));
        when(accountingHandler.handleNewSessionUsage(any(), anyString(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        // Act
        UniAssertSubscriber<Void> subscriber = interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Assert
        subscriber.awaitItem().assertCompleted();
        verify(accountingHandler).handleNewSessionUsage(eq(request), eq(traceId), any(), any());
    }

    @Test
    void testHandleInterim_ExistingSession_Success() {
        // Arrange
        UserSessionData userData = createMockUserData("ACTIVE");
        Session existingSession = new Session();
        existingSession.setSessionId("sess-001");
        existingSession.setSessionTime(50); // Current time is 50, request is 100

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingHandler.findSessionById(any(), anyString())).thenReturn(existingSession);

        UpdateResult successResult = mock(UpdateResult.class);
        when(successResult.success()).thenReturn(true);

        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(successResult));
        when(sessionLifecycleManager.onSessionActivity(anyString(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        // Act
        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Assert
        verify(accountingUtil).updateSessionAndBalance(any(), any(), eq(request), any());
        verify(sessionLifecycleManager).onSessionActivity(anyString(), anyString());
    }

    @Test
    void testHandleInterim_UserBarred() {
        // Arrange
        UserSessionData userData = createMockUserData("BARRED");
        Session session = new Session();
        session.setSessionId("sess-001");

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingHandler.findSessionById(any(), anyString())).thenReturn(session);

        // Act
        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Assert
        verifyNoInteractions(accountingUtil);
        // Note: generateAndSendCDR is private and static-mapped via CdrMappingUtil,
        // in a real scenario you might verify the accountProducer interaction inside it.
    }

    @Test
    void testHandleInterim_DuplicateSessionTime() {
        // Arrange: Request time (100) <= Session time (100)
        UserSessionData userData = createMockUserData("ACTIVE");
        Session session = new Session();
        session.setSessionId("sess-001");
        session.setSessionTime(100);

        when(cacheUtil.getUserData(anyString())).thenReturn(Uni.createFrom().item(userData));
        when(accountingHandler.findSessionById(any(), anyString())).thenReturn(session);

        // Act
        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Assert
        verifyNoInteractions(accountingUtil);
    }

    @Test
    void testHandleInterim_CircuitBreakerOpen() {
        // Arrange
        when(cacheUtil.getUserData(anyString()))
                .thenReturn(Uni.createFrom().failure(new CircuitBreakerOpenException()));

        // Act
        interimHandler.handleInterim(request, traceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted(); // Should recover with voidItem

        // Verify no further processing happened
        verify(accountingHandler, times(0)).handleNewSessionUsage(any(), any(), any(), any());
    }

    @Test
    void testProcessAccountingRequest_WithCachedGroupData() {
        // 1. Setup Request with a high session time (e.g., 100)
        AccountingRequestDto customRequest = new AccountingRequestDto(
                "ev-1", "sess-001", "1.1.1.1", "user1",
                AccountingRequestDto.ActionType.INTERIM_UPDATE,
                0, 0, 100, java.time.Instant.now(),
                "p1", "1.1.1.1", 0, 0, 0, "nas1"
        );

        UserSessionData userData = new UserSessionData();
        userData.setSessions(new ArrayList<>()); // Empty list so findSessionById returns null
        userData.setGroupId("grp1");

        // The 3rd parameter in handleInterim is passed to processAccountingRequest as 'cachedGroupData'
        String mockCsvData = "ignore,5,ACTIVE,3600";

        // Mock Cache
        when(cacheUtil.getUserData(any())).thenReturn(Uni.createFrom().item(userData));

        // Force findSessionById to return null to trigger createSession logic
        when(accountingHandler.findSessionById(any(), anyString())).thenReturn(null);

        // Now these stubs WILL be used because we bypass the early returns
        UpdateResult result = mock(UpdateResult.class);
        when(result.success()).thenReturn(true);

        when(accountingUtil.updateSessionAndBalance(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(result));

        when(sessionLifecycleManager.onSessionActivity(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        // Act
        interimHandler.handleInterim(customRequest, mockCsvData)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Assert
        verify(accountingUtil).updateSessionAndBalance(any(), any(), any(), any());
    }


    private UserSessionData createMockUserData(String status) {
        UserSessionData data = new UserSessionData();
        data.setUserStatus(status);
        data.setSessions(new ArrayList<>());
        data.setGroupId("group1");
        data.setConcurrency(1L);
        data.setSessionTimeOut("3600");
        return data;
    }
}