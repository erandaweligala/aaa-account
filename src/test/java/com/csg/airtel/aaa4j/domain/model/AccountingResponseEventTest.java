package com.csg.airtel.aaa4j.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountingResponseEventTest {

    @Test
    void testRecordCreation() {
        AccountingResponseEvent.EventType eventType = AccountingResponseEvent.EventType.COA;
        LocalDateTime eventTime = LocalDateTime.now();
        String sessionId = "SES001";
        AccountingResponseEvent.ResponseAction action = AccountingResponseEvent.ResponseAction.SUCCESS;
        String message = "Operation successful";
        Long totalQuotaBalance = 5000L;
        Map<String, String> qosParameters = Map.of("bandwidth", "100Mbps");

        AccountingResponseEvent event = new AccountingResponseEvent(
                eventType, eventTime, sessionId, action,
                message, totalQuotaBalance, qosParameters
        );

        assertEquals(eventType, event.eventType());
        assertEquals(eventTime, event.eventTime());
        assertEquals(sessionId, event.sessionId());
        assertEquals(action, event.action());
        assertEquals(message, event.message());
        assertEquals(totalQuotaBalance, event.totalQuotaBalance());
        assertEquals(qosParameters, event.qosParameters());
    }

    @Test
    void testResponseActionDisconnect() {
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES001",
                AccountingResponseEvent.ResponseAction.DISCONNECT,
                "Disconnecting",
                0L,
                Map.of()
        );

        assertEquals(AccountingResponseEvent.ResponseAction.DISCONNECT, event.action());
    }

    @Test
    void testResponseActionFupApply() {
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES002",
                AccountingResponseEvent.ResponseAction.FUP_APPLY,
                "FUP applied",
                1000L,
                Map.of("fup", "enabled")
        );

        assertEquals(AccountingResponseEvent.ResponseAction.FUP_APPLY, event.action());
    }

    @Test
    void testResponseActionPackageUpgrade() {
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES003",
                AccountingResponseEvent.ResponseAction.PACKAGE_UPGRADE,
                "Package upgraded",
                10000L,
                Map.of()
        );

        assertEquals(AccountingResponseEvent.ResponseAction.PACKAGE_UPGRADE, event.action());
    }

    @Test
    void testResponseActionInternalError() {
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES004",
                AccountingResponseEvent.ResponseAction.INTERNAL_ERROR,
                "Internal error occurred",
                0L,
                Map.of()
        );

        assertEquals(AccountingResponseEvent.ResponseAction.INTERNAL_ERROR, event.action());
    }

    @Test
    void testResponseActionIgnoreProcessing() {
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES005",
                AccountingResponseEvent.ResponseAction.IGNORE_PROCESSING,
                "Ignoring",
                0L,
                Map.of()
        );

        assertEquals(AccountingResponseEvent.ResponseAction.IGNORE_PROCESSING, event.action());
    }

    @Test
    void testResponseActionSuccess() {
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES006",
                AccountingResponseEvent.ResponseAction.SUCCESS,
                "Success",
                5000L,
                Map.of()
        );

        assertEquals(AccountingResponseEvent.ResponseAction.SUCCESS, event.action());
    }

    @Test
    void testEventTypeCOA() {
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES007",
                AccountingResponseEvent.ResponseAction.SUCCESS,
                "COA event",
                0L,
                Map.of()
        );

        assertEquals(AccountingResponseEvent.EventType.COA, event.eventType());
    }

    @Test
    void testRecordEquality() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> params = Map.of("key", "value");

        AccountingResponseEvent event1 = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA, now, "SES001",
                AccountingResponseEvent.ResponseAction.SUCCESS, "msg", 100L, params
        );

        AccountingResponseEvent event2 = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA, now, "SES001",
                AccountingResponseEvent.ResponseAction.SUCCESS, "msg", 100L, params
        );

        assertEquals(event1, event2);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    void testRecordToString() {
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES001",
                AccountingResponseEvent.ResponseAction.SUCCESS,
                "Test message",
                1000L,
                Map.of()
        );

        String result = event.toString();
        assertNotNull(result);
        assertTrue(result.contains("SES001"));
    }

    @Test
    void testResponseActionEnumValues() {
        assertEquals(6, AccountingResponseEvent.ResponseAction.values().length);
    }

    @Test
    void testEventTypeEnumValues() {
        assertEquals(1, AccountingResponseEvent.EventType.values().length);
        assertEquals(AccountingResponseEvent.EventType.COA,
                     AccountingResponseEvent.EventType.valueOf("COA"));
    }

    @Test
    void testWithEmptyQosParameters() {
        Map<String, String> emptyParams = new HashMap<>();
        AccountingResponseEvent event = new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                "SES001",
                AccountingResponseEvent.ResponseAction.SUCCESS,
                "message",
                0L,
                emptyParams
        );

        assertNotNull(event.qosParameters());
        assertTrue(event.qosParameters().isEmpty());
    }
}
