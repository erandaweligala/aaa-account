package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.coa.CoADisconnectResponse;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class COAServiceTest {

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

    @Test
    void testClearAllSessionsAndSendCOA_EmptySessions() {
        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(new ArrayList<>())
            .build();

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaHttpClient, never()).sendDisconnect(any());
    }

    @Test
    void testClearAllSessionsAndSendCOA_WithSessions() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));
        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        CoADisconnectResponse response = new CoADisconnectResponse(
            "ACK", "session-1", "Success"
        );
        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().item(response));

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaHttpClient).sendDisconnect(any(AccountingResponseEvent.class));
        verify(monitoringService).recordCOARequest();
    }

    @Test
    void testClearAllSessionsAndSendCOA_SpecificSession() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));
        sessions.add(createSession("session-2"));
        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        CoADisconnectResponse response = new CoADisconnectResponse(
            "ACK", "session-1", "Success"
        );
        when(coaHttpClient.sendDisconnect(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().item(response));

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userData, "testuser", "session-1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        verify(coaHttpClient, times(1)).sendDisconnect(any(AccountingResponseEvent.class));
    }

    @Test
    void testProduceAccountingResponseEvent() {
        AccountingResponseEvent event = new AccountingResponseEvent(
            AccountingResponseEvent.EventType.COA,
            LocalDateTime.now(),
            "session-1",
            AccountingResponseEvent.ResponseAction.DISCONNECT,
            "Test",
            0L,
            java.util.Map.of()
        );
        Session session = createSession("session-1");

        CoADisconnectResponse response = new CoADisconnectResponse(
            "ACK", "session-1", "Success"
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
    void testClearAllSessionsAndSendCOAMassageQue_WithSessions() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));
        sessions.add(createSession("session-2"));

        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        when(accountProducer.produceAccountingResponseEvent(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().voidItem());

        coaService.clearAllSessionsAndSendCOAMassageQue(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountProducer, times(2)).produceAccountingResponseEvent(any(AccountingResponseEvent.class));
        verify(monitoringService, times(2)).recordCOARequest();
    }

    @Test
    void testClearAllSessionsAndSendCOAMassageQue_EmptySessions() {
        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(new ArrayList<>())
            .build();

        coaService.clearAllSessionsAndSendCOAMassageQue(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountProducer, never()).produceAccountingResponseEvent(any());
        verify(monitoringService, never()).recordCOARequest();
    }

    @Test
    void testClearAllSessionsAndSendCOAMassageQue_SpecificSession() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));
        sessions.add(createSession("session-2"));

        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        when(accountProducer.produceAccountingResponseEvent(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().voidItem());

        coaService.clearAllSessionsAndSendCOAMassageQue(userData, "testuser", "session-1")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountProducer, times(1)).produceAccountingResponseEvent(any(AccountingResponseEvent.class));
        verify(monitoringService, times(1)).recordCOARequest();
    }

    @Test
    void testClearAllSessionsAndSendCOAMassageQue_ProducerFailure() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-1"));

        UserSessionData userData = UserSessionData.builder()
            .userName("testuser")
            .sessions(sessions)
            .build();

        when(accountProducer.produceAccountingResponseEvent(any(AccountingResponseEvent.class)))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Producer error")));

        coaService.clearAllSessionsAndSendCOAMassageQue(userData, "testuser", null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountProducer).produceAccountingResponseEvent(any(AccountingResponseEvent.class));
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
}
