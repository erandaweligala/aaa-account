package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CoAHttpClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class COAServiceAdvancedTest {

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private MonitoringService monitoringService;

    @Mock
    private CoAHttpClient coaHttpClient;

    private COAService coaService;

    @BeforeEach
    void setUp() {
        coaService = new COAService(accountProducer, monitoringService, coaHttpClient);
    }

    // pls review this test classes lot of  errors found
    @Test
    void testClearAllSessionsAndSendCOA_MultipleSessions() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));
        sessions.add(createSession("session-2"));
        sessions.add(createSession("session-3"));

        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        CoAHttpClient.CoAResponse response = new CoAHttpClient.CoAResponse(
            true, 200, "ACK", "session"
        );
        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().item(response));

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.getSessions().isEmpty());
        verify(coaHttpClient, times(3)).sendDisconnect(any(AccountingResponseEvent.class));
        verify(monitoringService, times(3)).recordCOARequest();
    }

    @Test
    void testClearAllSessionsAndSendCOA_NullSessions() {
        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(null)
            .build();

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaHttpClient, never()).sendDisconnect(any());
    }

    @Test
    void testClearAllSessionsAndSendCOA_HttpClientFailure() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));

        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("HTTP error")));

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaHttpClient).sendDisconnect(any(AccountingResponseEvent.class));
    }

    @Test
    void testClearAllSessionsAndSendCOA_NegativeResponse() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));

        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        CoAHttpClient.CoAResponse response = new CoAHttpClient.CoAResponse(
            false, 500, "ERROR", "session-1"
        );
        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().item(response));

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.getSessions().isEmpty());
    }

    @Test
    void testClearSessionsBySessionData_EmptyList() {
        List<com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex.SessionExpiryData> emptyList =
            new ArrayList<>();

        Integer count = coaService.clearSessionsBySessionData(emptyList)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(0, count);
        verify(coaHttpClient, never()).sendDisconnect(any());
    }

    @Test
    void testProduceAccountingResponseEvent_Success() {
        AccountingResponseEvent event = createEvent();
        Session session = createSession("session-1");

        CoAHttpClient.CoAResponse response = new CoAHttpClient.CoAResponse(
            true, 200, "ACK", "session-1"
        );
        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().item(response));

        coaService.produceAccountingResponseEvent(event, session, "testuser")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(coaHttpClient).sendDisconnect(event);
        verify(monitoringService).recordCOARequest();
    }

    @Test
    void testProduceAccountingResponseEvent_Failure() {
        AccountingResponseEvent event = createEvent();
        Session session = createSession("session-1");

        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Network error")));

        coaService.produceAccountingResponseEvent(event, session, "testuser")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(coaHttpClient).sendDisconnect(event);
    }

    @Test
    void testProduceAccountingResponseEvent_NullSession() {
        AccountingResponseEvent event = createEvent();

        CoAHttpClient.CoAResponse response = new CoAHttpClient.CoAResponse(
            true, 200, "ACK", "session-1"
        );
        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().item(response));

        coaService.produceAccountingResponseEvent(event, null, "testuser")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(coaHttpClient).sendDisconnect(event);
    }

    @Test
    void testClearAllSessionsAndSendCOA_SpecificSessionMatched() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));
        sessions.add(createSession("session-2"));

        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        CoAHttpClient.CoAResponse response = new CoAHttpClient.CoAResponse(
            true, 200, "ACK", "session-1"
        );
        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().item(response));

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", "session-1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals(1, result.getSessions().size());
        assertEquals("session-2", result.getSessions().get(0).getSessionId());
        verify(coaHttpClient, times(1)).sendDisconnect(any(AccountingResponseEvent.class));
    }

    @Test
    void testClearAllSessionsAndSendCOA_SpecificSessionNotMatched() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));
        sessions.add(createSession("session-2"));

        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        CoAHttpClient.CoAResponse response = new CoAHttpClient.CoAResponse(
            true, 200, "ACK", "session"
        );
        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().item(response));

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", "session-3")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals(2, result.getSessions().size());
        verify(coaHttpClient, never()).sendDisconnect(any(AccountingResponseEvent.class));
    }

    private Session createSession(String sessionId) {
        return new Session(
            sessionId,
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            0,
            0L,
            "192.168.1.1",
            "10.0.0.1",
            "port-1",
            false,
            0,
            null,
            "testuser",
            null,
            null,
            null,
            0
        );
    }

    private AccountingResponseEvent createEvent() {
        return new AccountingResponseEvent(
            AccountingResponseEvent.EventType.COA,
            LocalDateTime.now(),
            "session-1",
            AccountingResponseEvent.ResponseAction.DISCONNECT,
            "Test",
            0L,
            java.util.Map.of(
                "username", "testuser",
                "sessionId", "session-1",
                "nasIP", "10.0.0.1",
                "framedIP", "192.168.1.1"
            )
        );
    }
}
