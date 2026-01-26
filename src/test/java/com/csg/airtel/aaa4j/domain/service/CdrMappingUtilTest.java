package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.model.cdr.EventTypes;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CdrMappingUtilTest {

    @Test
    void testBuildStartCDREvent() {
        AccountingRequestDto request = createSampleRequest(AccountingRequestDto.ActionType.START);
        Session session = createSampleSession();

        AccountingCDREvent event = CdrMappingUtil.buildStartCDREvent(request, session);

        assertNotNull(event);
        assertEquals(EventTypes.ACCOUNTING_START.name(), event.getEventType());
        assertEquals("1.0", event.getEventVersion());
        assertEquals("AAA-Service", event.getSource());
        assertNotNull(event.getPayload());
        assertNotNull(event.getPayload().getSession());
        assertNotNull(event.getPayload().getUser());
        assertNotNull(event.getPayload().getNetwork());
        assertNotNull(event.getPayload().getAccounting());
        assertEquals("Start", event.getPayload().getAccounting().getAcctStatusType());
        assertEquals(0L, event.getPayload().getAccounting().getAcctInputOctets());
    }

    @Test
    void testBuildInterimCDREvent() {
        AccountingRequestDto request = createSampleRequest(AccountingRequestDto.ActionType.INTERIM_UPDATE);
        Session session = createSampleSession();

        AccountingCDREvent event = CdrMappingUtil.buildInterimCDREvent(request, session);

        assertNotNull(event);
        assertEquals(EventTypes.ACCOUNTING_INTERIM.name(), event.getEventType());
        assertEquals("Interim-Update", event.getPayload().getAccounting().getAcctStatusType());
        assertEquals(request.sessionTime(), event.getPayload().getAccounting().getAcctSessionTime());
    }

    @Test
    void testBuildStopCDREvent() {
        AccountingRequestDto request = createSampleRequest(AccountingRequestDto.ActionType.STOP);
        Session session = createSampleSession();

        AccountingCDREvent event = CdrMappingUtil.buildStopCDREvent(request, session);

        assertNotNull(event);
        assertEquals(EventTypes.ACCOUNTING_STOP.name(), event.getEventType());
        assertEquals("Stop", event.getPayload().getAccounting().getAcctStatusType());
        assertNotNull(event.getPayload().getSession().getSessionStopTime());
    }

    @Test
    void testBuildCoaDisconnectCDREvent() {
        Session session = createSampleSession();
        String username = "testuser";

        AccountingCDREvent event = CdrMappingUtil.buildCoaDisconnectCDREvent(session, username);

        assertNotNull(event);
        assertEquals(EventTypes.COA_DISCONNECT_REQUEST.name(), event.getEventType());
        assertEquals("radius-server", event.getSource());
        assertNotNull(event.getPayload().getCoa());
        assertEquals("Disconnect-Request", event.getPayload().getCoa().getCoaType());
        assertEquals(40, event.getPayload().getCoa().getCoaCode());
        assertEquals(3799, event.getPayload().getCoa().getDestinationPort());
        assertNotNull(event.getPayload().getRadius());
        assertNotNull(event.getPayload().getRadius().getAttributes());
        assertTrue(event.getPayload().getRadius().getAttributes().size() > 0);
    }

    @Test
    void testBuildSessionCdr() {
        AccountingRequestDto request = createSampleRequest(AccountingRequestDto.ActionType.START);
        var sessionCdr = CdrMappingUtil.buildSessionCdr(request, 0, EventTypes.ACCOUNTING_START.name());

        assertNotNull(sessionCdr);
        assertEquals(request.sessionId(), sessionCdr.getSessionId());
        assertEquals(request.nasIP(), sessionCdr.getNasIpAddress());
        assertEquals(request.nasIdentifier(), sessionCdr.getNasIdentifier());
        assertNull(sessionCdr.getSessionStopTime());
    }

    @Test
    void testBuildUserCdr() {
        AccountingRequestDto request = createSampleRequest(AccountingRequestDto.ActionType.START);
        String groupId = "group-123";

        var userCdr = CdrMappingUtil.buildUserCdr(request, groupId);

        assertNotNull(userCdr);
        assertEquals(request.username(), userCdr.getUserName());
        assertEquals(groupId, userCdr.getGroupId());
    }

    @Test
    void testBuildNetworkCdr() {
        AccountingRequestDto request = createSampleRequest(AccountingRequestDto.ActionType.START);

        var networkCdr = CdrMappingUtil.buildNetworkCdr(request);

        assertNotNull(networkCdr);
        assertEquals(request.framedIPAddress(), networkCdr.getFramedIpAddress());
        assertEquals(request.nasIP(), networkCdr.getCalledStationId());
    }

    @Test
    void testAccountingMetrics_ForStart() {
        var metrics = CdrMappingUtil.AccountingMetrics.forStart();

        assertNotNull(metrics);
        assertEquals("Start", metrics.getAcctStatusType());
        assertEquals(EventTypes.ACCOUNTING_START.name(), metrics.getEventType());
        assertEquals(0, metrics.getSessionTime());
        assertEquals(0L, metrics.getInputOctets());
        assertEquals(0L, metrics.getOutputOctets());
    }

    @Test
    void testAccountingMetrics_ForInterim() {
        AccountingRequestDto request = createSampleRequest(AccountingRequestDto.ActionType.INTERIM_UPDATE);
        var metrics = CdrMappingUtil.AccountingMetrics.forInterim(request);

        assertNotNull(metrics);
        assertEquals("Interim-Update", metrics.getAcctStatusType());
        assertEquals(EventTypes.ACCOUNTING_INTERIM.name(), metrics.getEventType());
        assertEquals(request.sessionTime(), metrics.getSessionTime());
    }

    @Test
    void testAccountingMetrics_ForStop() {
        AccountingRequestDto request = createSampleRequest(AccountingRequestDto.ActionType.STOP);
        var metrics = CdrMappingUtil.AccountingMetrics.forStop(request);

        assertNotNull(metrics);
        assertEquals("Stop", metrics.getAcctStatusType());
        assertEquals(EventTypes.ACCOUNTING_STOP.name(), metrics.getEventType());
        assertEquals(request.sessionTime(), metrics.getSessionTime());
    }

    private AccountingRequestDto createSampleRequest(AccountingRequestDto.ActionType actionType) {
        return new AccountingRequestDto( //not assign in curect values
            "testuser",
            "session-123",
            actionType,
            1000,
            500L,
            500L,
            1,
            1,
            "192.168.1.1",
            "10.0.0.1",
            "nas-id",
            "port-1",
            0,
            LocalDateTime.now()
        );
    }

    private Session createSampleSession() {
        return new Session(
            "session-123",
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
            "service-1",
            "testuser",
            "bucket-1",
            "group-1",
            null,
            0
        );
    }
}
