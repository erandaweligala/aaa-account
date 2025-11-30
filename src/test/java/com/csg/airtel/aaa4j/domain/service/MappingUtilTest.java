package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MappingUtilTest {

    @Test
    void testCreateResponseWithFourParameters() {
        AccountingRequestDto request = new AccountingRequestDto(
                "EVT001", "SES001", "192.168.1.1", "testuser",
                AccountingRequestDto.ActionType.START,
                0, 0, 0, Instant.now(), "PORT1", "10.0.0.1",
                0, 0, 0, "NAS1"
        );

        String message = "Test message";
        AccountingResponseEvent.EventType eventType = AccountingResponseEvent.EventType.COA;
        AccountingResponseEvent.ResponseAction responseAction = AccountingResponseEvent.ResponseAction.DISCONNECT;

        AccountingResponseEvent response = MappingUtil.createResponse(
                request, message, eventType, responseAction
        );

        assertNotNull(response);
        assertEquals(eventType, response.eventType());
        assertEquals(request.sessionId(), response.sessionId());
        assertEquals(responseAction, response.action());
        assertEquals(message, response.message());
        assertEquals(0L, response.totalQuotaBalance());
        assertNotNull(response.qosParameters());
        assertEquals("testuser", response.qosParameters().get("username"));
        assertEquals("SES001", response.qosParameters().get("sessionId"));
        assertEquals("192.168.1.1", response.qosParameters().get("nasIP"));
        assertEquals("10.0.0.1", response.qosParameters().get("framedIP"));
    }

    @Test
    void testCreateResponseWithNonCOAEventType() {
        AccountingRequestDto request = new AccountingRequestDto(
                "EVT002", "SES002", "192.168.1.2", "user2",
                AccountingRequestDto.ActionType.STOP,
                0, 0, 0, Instant.now(), "PORT2", "10.0.0.2",
                0, 0, 0, "NAS2"
        );

        AccountingResponseEvent response = MappingUtil.createResponse(
                request, "message", AccountingResponseEvent.EventType.COA,
                AccountingResponseEvent.ResponseAction.SUCCESS
        );

        assertNotNull(response);
        assertNotNull(response.qosParameters());
        assertFalse(response.qosParameters().isEmpty());
    }

    @Test
    void testCreateResponseWithFiveParameters() {
        String sessionId = "SES003";
        String message = "Disconnect message";
        String nasIP = "192.168.1.3";
        String framedIPAddress = "10.0.0.3";
        String userName = "testuser3";

        AccountingResponseEvent response = MappingUtil.createResponse(
                sessionId, message, nasIP, framedIPAddress, userName
        );

        assertNotNull(response);
        assertEquals(AccountingResponseEvent.EventType.COA, response.eventType());
        assertEquals(sessionId, response.sessionId());
        assertEquals(AccountingResponseEvent.ResponseAction.DISCONNECT, response.action());
        assertEquals(message, response.message());
        assertEquals(0L, response.totalQuotaBalance());
        assertNotNull(response.qosParameters());
        assertEquals(userName, response.qosParameters().get("username"));
        assertEquals(sessionId, response.qosParameters().get("sessionId"));
        assertEquals(nasIP, response.qosParameters().get("nasIP"));
        assertEquals(framedIPAddress, response.qosParameters().get("framedIP"));
    }

    @Test
    void testCreateBalance() {
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketId(123L);
        bucket.setExpiryDate(LocalDateTime.now().plusDays(30));
        bucket.setPriority(1L);
        bucket.setCurrentBalance(5000L);
        bucket.setInitialBalance(10000L);
        bucket.setServiceStartDate(LocalDateTime.now());
        bucket.setServiceId(456L);
        bucket.setStatus("ACTIVE");
        bucket.setConsumptionLimit(1000L);
        bucket.setTimeWindow("HOURLY");
        bucket.setConsumptionTimeWindow(3600L);
        bucket.setBucketUser("testuser");
        bucket.setBucketExpiryDate(LocalDateTime.now().plusMonths(6));

        Balance balance = MappingUtil.createBalance(bucket);

        assertNotNull(balance);
        assertEquals("123", balance.getBucketId());
        assertEquals(bucket.getExpiryDate(), balance.getServiceExpiry());
        assertEquals(1L, balance.getPriority());
        assertEquals(5000L, balance.getQuota());
        assertEquals(10000L, balance.getInitialBalance());
        assertEquals(bucket.getServiceStartDate(), balance.getServiceStartDate());
        assertEquals("456", balance.getServiceId());
        assertEquals("ACTIVE", balance.getServiceStatus());
        assertEquals(1000L, balance.getConsumptionLimit());
        assertEquals("HOURLY", balance.getTimeWindow());
        assertEquals(3600L, balance.getConsumptionLimitWindow());
        assertEquals("testuser", balance.getBucketUsername());
        assertEquals(bucket.getBucketExpiryDate(), balance.getBucketExpiryDate());
    }

    @Test
    void testCreateBalanceWithMinimalData() {
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketId(1L);
        bucket.setServiceId(2L);
        bucket.setCurrentBalance(0L);
        bucket.setInitialBalance(0L);
        bucket.setPriority(0L);

        Balance balance = MappingUtil.createBalance(bucket);

        assertNotNull(balance);
        assertEquals("1", balance.getBucketId());
        assertEquals("2", balance.getServiceId());
        assertEquals(0L, balance.getQuota());
        assertEquals(0L, balance.getInitialBalance());
    }

    @Test
    void testCreateResponseEventTime() {
        AccountingRequestDto request = new AccountingRequestDto(
                "EVT001", "SES001", "192.168.1.1", "user",
                AccountingRequestDto.ActionType.START,
                0, 0, 0, Instant.now(), "PORT1", "10.0.0.1",
                0, 0, 0, "NAS1"
        );

        AccountingResponseEvent response = MappingUtil.createResponse(
                request, "msg", AccountingResponseEvent.EventType.COA,
                AccountingResponseEvent.ResponseAction.SUCCESS
        );

        assertNotNull(response.eventTime());
        assertTrue(response.eventTime().isBefore(LocalDateTime.now().plusSeconds(1)));
        assertTrue(response.eventTime().isAfter(LocalDateTime.now().minusSeconds(1)));
    }

    @Test
    void testQosParametersAreImmutable() {
        String sessionId = "SES001";
        AccountingResponseEvent response = MappingUtil.createResponse(
                sessionId, "msg", "192.168.1.1", "10.0.0.1", "user"
        );

        Map<String, String> qosParams = response.qosParameters();
        assertThrows(UnsupportedOperationException.class, () -> {
            qosParams.put("newKey", "newValue");
        });
    }
}
