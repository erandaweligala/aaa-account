package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.ProcessType;
import com.csg.airtel.aaa4j.domain.model.cdr.*;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CdrMappingUtilTest {

    @Mock
    private AccountProducer accountProducer;

    @Test
    void testAccountingMetricsForStart() {
        CdrMappingUtil.AccountingMetrics metrics = CdrMappingUtil.AccountingMetrics.forStart();

        assertEquals("Start", metrics.getAcctStatusType());
        assertEquals(EventTypes.ACCOUNTING_START.name(), metrics.getEventType());
        assertEquals(0, metrics.getSessionTime());
        assertEquals(0L, metrics.getInputOctets());
        assertEquals(0L, metrics.getOutputOctets());
        assertEquals(0, metrics.getInputGigawords());
        assertEquals(0, metrics.getOutputGigawords());
    }

    @Test
    void testAccountingMetricsForInterim() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.INTERIM_UPDATE, 100, 1000, 2000, 1, 2);
        CdrMappingUtil.AccountingMetrics metrics = CdrMappingUtil.AccountingMetrics.forInterim(request);

        assertEquals("Interim-Update", metrics.getAcctStatusType());
        assertEquals(EventTypes.ACCOUNTING_INTERIM.name(), metrics.getEventType());
        assertEquals(100, metrics.getSessionTime());
        assertEquals(1000L, metrics.getInputOctets());
        assertEquals(2000L, metrics.getOutputOctets());
        assertEquals(1, metrics.getInputGigawords());
        assertEquals(2, metrics.getOutputGigawords());
    }

    @Test
    void testAccountingMetricsForStop() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.STOP, 200, 3000, 4000, 3, 4);
        CdrMappingUtil.AccountingMetrics metrics = CdrMappingUtil.AccountingMetrics.forStop(request);

        assertEquals("Stop", metrics.getAcctStatusType());
        assertEquals(EventTypes.ACCOUNTING_STOP.name(), metrics.getEventType());
        assertEquals(200, metrics.getSessionTime());
        assertEquals(3000L, metrics.getInputOctets());
        assertEquals(4000L, metrics.getOutputOctets());
        assertEquals(3, metrics.getInputGigawords());
        assertEquals(4, metrics.getOutputGigawords());
    }

    @Test
    void testBuildStartCDREvent() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START, 0, 0, 0, 0, 0);
        Session session = createSession();

        AccountingCDREvent event = CdrMappingUtil.buildStartCDREvent(request, session);

        assertNotNull(event);
        assertNotNull(event.getEventId());
        assertEquals(EventTypes.ACCOUNTING_START.name(), event.getEventType());
        assertEquals("1.0", event.getEventVersion());
        assertEquals("AAA-Service", event.getSource());
        assertNotNull(event.getPayload());
        assertEquals("Start", event.getPayload().getAccounting().getAcctStatusType());
        assertEquals(0, event.getPayload().getAccounting().getAcctSessionTime());
    }

    @Test
    void testBuildInterimCDREvent() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.INTERIM_UPDATE, 100, 1000, 2000, 1, 2);
        Session session = createSession();

        AccountingCDREvent event = CdrMappingUtil.buildInterimCDREvent(request, session);

        assertNotNull(event);
        assertNotNull(event.getEventId());
        assertEquals(EventTypes.ACCOUNTING_INTERIM.name(), event.getEventType());
        assertEquals("1.0", event.getEventVersion());
        assertEquals("AAA-Service", event.getSource());
        assertNotNull(event.getPayload());
        assertEquals("Interim-Update", event.getPayload().getAccounting().getAcctStatusType());
        assertEquals(100, event.getPayload().getAccounting().getAcctSessionTime());
        assertEquals(1000L, event.getPayload().getAccounting().getAcctInputOctets());
        assertEquals(2000L, event.getPayload().getAccounting().getAcctOutputOctets());
    }

    @Test
    void testBuildStopCDREvent() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.STOP, 200, 3000, 4000, 3, 4);
        Session session = createSession();

        AccountingCDREvent event = CdrMappingUtil.buildStopCDREvent(request, session);

        assertNotNull(event);
        assertNotNull(event.getEventId());
        assertEquals(EventTypes.ACCOUNTING_STOP.name(), event.getEventType());
        assertEquals("1.0", event.getEventVersion());
        assertEquals("AAA-Service", event.getSource());
        assertNotNull(event.getPayload());
        assertEquals("Stop", event.getPayload().getAccounting().getAcctStatusType());
        assertEquals(200, event.getPayload().getAccounting().getAcctSessionTime());
        assertEquals(3000L, event.getPayload().getAccounting().getAcctInputOctets());
        assertEquals(4000L, event.getPayload().getAccounting().getAcctOutputOctets());
    }

    @Test
    void testBuildSessionCdrWithStartEvent() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START, 0, 0, 0, 0, 0);
        SessionCdr sessionCdr = CdrMappingUtil.buildSessionCdr(request, 0, EventTypes.ACCOUNTING_START.name());

        assertNotNull(sessionCdr);
        assertEquals(request.sessionId(), sessionCdr.getSessionId());
        assertEquals("0", sessionCdr.getSessionTime());
        assertEquals(request.timestamp(), sessionCdr.getStartTime());
        assertEquals(request.nasIdentifier(), sessionCdr.getNasIdentifier());
        assertEquals(request.nasIP(), sessionCdr.getNasIpAddress());
        assertNull(sessionCdr.getSessionStopTime());
    }

    @Test
    void testBuildSessionCdrWithStopEvent() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.STOP, 200, 3000, 4000, 3, 4);
        SessionCdr sessionCdr = CdrMappingUtil.buildSessionCdr(request, 200, "Stop");

        assertNotNull(sessionCdr);
        assertEquals(request.sessionId(), sessionCdr.getSessionId());
        assertEquals("200", sessionCdr.getSessionTime());
        assertEquals(request.timestamp(), sessionCdr.getStartTime());
        assertEquals(request.timestamp(), sessionCdr.getSessionStopTime());
    }

    @Test
    void testBuildUserCdr() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START, 0, 0, 0, 0, 0);
        User user = CdrMappingUtil.buildUserCdr(request);

        assertNotNull(user);
        assertEquals(request.username(), user.getUserName());
    }

    @Test
    void testBuildNetworkCdr() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START, 0, 0, 0, 0, 0);
        Network network = CdrMappingUtil.buildNetworkCdr(request);

        assertNotNull(network);
        assertEquals(request.framedIPAddress(), network.getFramedIpAddress());
        assertEquals(request.nasIP(), network.getCalledStationId());
    }

    @Test
    void testBuildAccountingCdr() {
        CdrMappingUtil.AccountingMetrics metrics = CdrMappingUtil.AccountingMetrics.forInterim(
                createAccountingRequest(ProcessType.INTERIM_UPDATE, 100, 1000, 2000, 1, 2)
        );
        Accounting accounting = CdrMappingUtil.buildAccountingCdr(metrics);

        assertNotNull(accounting);
        assertEquals("Interim-Update", accounting.getAcctStatusType());
        assertEquals(100, accounting.getAcctSessionTime());
        assertEquals(1000L, accounting.getAcctInputOctets());
        assertEquals(2000L, accounting.getAcctOutputOctets());
        assertEquals(0, accounting.getAcctInputPackets());
        assertEquals(0, accounting.getAcctOutputPackets());
        assertEquals(1, accounting.getAcctInputGigawords());
        assertEquals(2, accounting.getAcctOutputGigawords());
    }

    @Test
    void testBuildAccountingCdrWithNullValues() {
        // Create a minimal request for testing null value handling
        AccountingRequestDto request = createAccountingRequest(ProcessType.START, 0, 0, 0, 0, 0);
        CdrMappingUtil.AccountingMetrics metrics = CdrMappingUtil.AccountingMetrics.forStart();
        Accounting accounting = CdrMappingUtil.buildAccountingCdr(metrics);

        assertNotNull(accounting);
        assertEquals(0, accounting.getAcctSessionTime());
        assertEquals(0, accounting.getAcctInputGigawords());
        assertEquals(0, accounting.getAcctOutputGigawords());
    }

    @Test
    void testGenerateAndSendCDRSuccess() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START, 0, 0, 0, 0, 0);
        Session session = createSession();

        when(accountProducer.produceAccountingCDREvent(any(AccountingCDREvent.class)))
                .thenReturn(Uni.createFrom().voidItem());

        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildStartCDREvent);

        verify(accountProducer, times(1)).produceAccountingCDREvent(any(AccountingCDREvent.class));
    }

    @Test
    void testGenerateAndSendCDRWithProducerFailure() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START, 0, 0, 0, 0, 0);
        Session session = createSession();

        when(accountProducer.produceAccountingCDREvent(any(AccountingCDREvent.class)))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Producer failure")));

        assertDoesNotThrow(() ->
                CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildStartCDREvent)
        );

        verify(accountProducer, times(1)).produceAccountingCDREvent(any(AccountingCDREvent.class));
    }

    private AccountingRequestDto createAccountingRequest(ProcessType actionType, int sessionTime,
                                                          int inputOctets, int outputOctets,
                                                          int inputGigawords, int outputGigawords) {
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
                sessionTime,
                inputOctets,
                outputOctets,
                inputGigawords,
                outputGigawords
        );
    }

    private Session createSession() {
        return new Session(
                "session-id-123",
                LocalDateTime.now(),
                null,
                0,
                0L,
                "192.168.1.1",
                "10.0.0.1"
        );
    }
}
