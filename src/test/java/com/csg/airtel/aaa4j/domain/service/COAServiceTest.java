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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class COAServiceTest {

    @Mock AccountProducer accountProducer;
    @Mock MonitoringService monitoringService;
    @Mock CoAHttpClient coaHttpClient;

    @InjectMocks COAService coaService;

    private UserSessionData userSessionData;
    private Session session1;
    private Session session2;

    @BeforeEach
    void setUp() {
        session1 = new Session();
        session1.setSessionId("S1");
        session1.setNasIp("10.0.0.1");
        session1.setFramedId("frame1");
        session1.setUserName("user1");

        session2 = new Session();
        session2.setSessionId("S2");
        session2.setNasIp("10.0.0.2");
        session2.setFramedId("frame2");
        session2.setUserName(null); // This tests the null username ternary logic

        // Ensure the list is modifiable or correctly handled by the builder
        userSessionData = UserSessionData.builder()
                .sessions(List.of(session1, session2))
                .build();
    }

    @Test
    void testQueue_LogFailureDebug() {
        // Force retries to exhaust so the final onFailure invoke is called
        when(accountProducer.produceAccountingResponseEvent(any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Final Error")));

        coaService.clearAllSessionsAndSendCOAMassageQue(userSessionData, "user", "S1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(java.time.Duration.ofSeconds(2));

        // Even if retries exhaust, recoverWithNull makes it complete
        verify(monitoringService, never()).recordCOARequest();
    }


    // --- clearAllSessionsAndSendCOAMassageQue ---

    @Test
    void testQueue_EmptySessions() {
        coaService.clearAllSessionsAndSendCOAMassageQue(UserSessionData.builder().build(), "u", null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();
        verifyNoInteractions(accountProducer);
    }

    @Test
    void testQueue_CompleteFailureAfterRetries() {
        // Mock failure for all possible attempts
        when(accountProducer.produceAccountingResponseEvent(any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Final Failure")));

        UniAssertSubscriber<Void> subscriber = coaService
                .clearAllSessionsAndSendCOAMassageQue(userSessionData, "user", "S1")
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Wait for retries to exhaust
        subscriber.awaitItem(java.time.Duration.ofSeconds(3));

        // Because of .onFailure().recoverWithNull(), the Uni should still complete
        subscriber.assertCompleted();

        // Verify monitoring was never called
        verify(monitoringService, never()).recordCOARequest();
    }

    @Test
    void testQueue_PermanentFailureRecovery() {
        // Mock permanent failure that exceeds retry attempts
        when(accountProducer.produceAccountingResponseEvent(any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Permanent Error")));

        UniAssertSubscriber<Void> subscriber = coaService.clearAllSessionsAndSendCOAMassageQue(userSessionData, "user", "S1")
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Wait for retries to exhaust
        subscriber.awaitItem(java.time.Duration.ofSeconds(5));

        // Even though producer failed, recoverWithNull() makes the stream complete successfully
        subscriber.assertCompleted();

        // Ensure metrics were NOT recorded because of failure
        verify(monitoringService, never()).recordCOARequest();
    }

    @Test
    void testHttp_AllSessionsAck_ReturnsOriginalData() {
        CoADisconnectResponse ack1 = new CoADisconnectResponse("ACK", "S1", "Ok");
        CoADisconnectResponse ack2 = new CoADisconnectResponse("ACK", "S2", "Ok");

        when(coaHttpClient.sendDisconnect(any()))
                .thenReturn(Uni.createFrom().item(ack1))
                .thenReturn(Uni.createFrom().item(ack2));

        when(accountProducer.produceAccountingCDREvent(any())).thenReturn(Uni.createFrom().voidItem());

        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userSessionData, "user", null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().getItem();

        // Verify original data returned because there were no NAKs
        assertEquals(2, result.getSessions().size());
        verify(monitoringService, times(2)).recordCOARequest();
    }

    @Test
    void testHttp_FilteringNoMatch() {
        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userSessionData, "user", "NON_EXISTENT")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().getItem();

        assertEquals(2, result.getSessions().size());
        verifyNoInteractions(coaHttpClient);
    }

//    @Test
//    void testHttp_TotalFailure() {
//        when(coaHttpClient.sendDisconnect(any()))
//                .thenReturn(Uni.createFrom().failure(new RuntimeException("Down")));
//
//        UserSessionData result = coaService.clearAllSessionsAndSendCOA(userSessionData, "user", "S1")
//                .subscribe().withSubscriber(UniAssertSubscriber.create())
//                .awaitItem().getItem();
//
//        assertTrue(result.getSessions().isEmpty());
//    }


    @Test
    void testProduceResponse_NakBranch() {
        // 1. Create a valid AccountingResponseEvent using the full constructor
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA, // Assuming EventType is an Enum
                java.time.LocalDateTime.now(),
                "S1",
                AccountingResponseEvent.ResponseAction.DISCONNECT, // Assuming ResponseAction is an Enum
                "10.0.0.1",
                12345L,
                java.util.Collections.emptyMap()
        );

        CoADisconnectResponse nak = new CoADisconnectResponse("FAILED", "S1", "Bad");

        // Mocking the behavior
        when(coaHttpClient.sendDisconnect(any())).thenReturn(Uni.createFrom().item(nak));

        // Execute the service method
        coaService.produceAccountingResponseEvent(event, session1, "user")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        // Verification
        verify(monitoringService, never()).recordCOARequest();
        verify(accountProducer, never()).produceAccountingCDREvent(any());
    }

    @Test
    void testHttp_MixedAckAndNak() {
        // Prepare mock responses
        CoADisconnectResponse ackResponse = new CoADisconnectResponse("ACK", "S1", "Success");
        CoADisconnectResponse nakResponse = new CoADisconnectResponse("NAK", "S2", "Failed");

        // Use consecutive calling for Multi item processing
        when(coaHttpClient.sendDisconnect(any()))
                .thenReturn(Uni.createFrom().item(ackResponse))
                .thenReturn(Uni.createFrom().item(nakResponse));

        // Mock CDR production
        when(accountProducer.produceAccountingCDREvent(any())).thenReturn(Uni.createFrom().voidItem());

        // Execute
        UniAssertSubscriber<UserSessionData> subscriber = coaService
                .clearAllSessionsAndSendCOA(userSessionData, "testUser", null)
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Assert
        UserSessionData result = subscriber.awaitItem().getItem();
        assertNotNull(result);

        // Logic: NAK sessions (S2) are filtered out. Only ACK sessions (S1) remain.
        assertEquals(1, result.getSessions().size());
        assertEquals("S1", result.getSessions().get(0).getSessionId());

        verify(monitoringService, times(1)).recordCOARequest();
        verify(accountProducer, times(1)).produceAccountingCDREvent(any());
    }

    @Test
    void testHttp_AllFailed_ShouldReturnEmptySessions() {
        when(coaHttpClient.sendDisconnect(any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Connection Refused")));

        UniAssertSubscriber<UserSessionData> subscriber = coaService
                .clearAllSessionsAndSendCOA(userSessionData, "testUser", null)
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        UserSessionData result = subscriber.awaitItem().getItem();

        // recoverWithItem(new CoAResult(..., false)) results in all sessions being treated as NAK
        assertTrue(result.getSessions().isEmpty());
    }

//    @Test
//    void testQueue_FailureAndRetry() {
//        when(accountProducer.produceAccountingResponseEvent(any()))
//                .thenReturn(Uni.createFrom().failure(new RuntimeException("Kafka Down")))
//                .thenReturn(Uni.createFrom().voidItem()); // Success on retry
//
//        when(accountProducer.produceAccountingCDREvent(any())).thenReturn(Uni.createFrom().voidItem());
//
//        coaService.clearAllSessionsAndSendCOAMassageQue(userSessionData, "testUser", "S1")
//                .subscribe().withSubscriber(UniAssertSubscriber.create())
//                .assertCompleted();
//
//        verify(accountProducer, atLeast(2)).produceAccountingResponseEvent(any());
//    }
}