package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.*;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MappingUtilTest {

    @Test
    void testCreateResponse_WithCoAEventType() {
        AccountingRequestDto request = createSampleRequest();
        String message = "Test message";
        AccountingResponseEvent.EventType eventType = AccountingResponseEvent.EventType.COA;
        AccountingResponseEvent.ResponseAction action = AccountingResponseEvent.ResponseAction.DISCONNECT;

        AccountingResponseEvent response = MappingUtil.createResponse(request, message, eventType, action);

        assertNotNull(response);
        assertEquals(eventType, response.eventType());
        assertEquals(action, response.responseAction());
        assertEquals(message, response.message());
        assertEquals(request.sessionId(), response.sessionId());
        assertNotNull(response.attributes());
        assertEquals(4, response.attributes().size());
        assertEquals(request.username(), response.attributes().get("username"));
        assertEquals(request.sessionId(), response.attributes().get("sessionId"));
        assertEquals(request.nasIP(), response.attributes().get("nasIP"));
        assertEquals(request.framedIPAddress(), response.attributes().get("framedIP"));
    }

    @Test
    void testCreateResponse_WithNonCoAEventType() {
        AccountingRequestDto request = createSampleRequest();
        String message = "Test message";
        AccountingResponseEvent.EventType eventType = AccountingResponseEvent.EventType.ACCOUNTING;
        AccountingResponseEvent.ResponseAction action = AccountingResponseEvent.ResponseAction.ACK;

        AccountingResponseEvent response = MappingUtil.createResponse(request, message, eventType, action);

        assertNotNull(response);
        assertEquals(eventType, response.eventType());
        assertEquals(action, response.responseAction());
        assertEquals(message, response.message());
        assertTrue(response.attributes().isEmpty());
    }

    @Test
    void testCreateResponse_WithSessionIdAndNasInfo() {
        String sessionId = "session-123";
        String message = "Disconnect message";
        String nasIP = "10.0.0.1";
        String framedIP = "192.168.1.1";
        String userName = "testuser";

        AccountingResponseEvent response = MappingUtil.createResponse(
            sessionId, message, nasIP, framedIP, userName
        );

        assertNotNull(response);
        assertEquals(AccountingResponseEvent.EventType.COA, response.eventType());
        assertEquals(AccountingResponseEvent.ResponseAction.DISCONNECT, response.responseAction());
        assertEquals(message, response.message());
        assertEquals(sessionId, response.sessionId());
        assertNotNull(response.attributes());
        assertEquals(userName, response.attributes().get("username"));
        assertEquals(sessionId, response.attributes().get("sessionId"));
        assertEquals(nasIP, response.attributes().get("nasIP"));
        assertEquals(framedIP, response.attributes().get("framedIP"));
    }

    @Test
    void testCreateBalance_AllFields() {
        ServiceBucketInfo bucket = createSampleBucket();

        Balance balance = MappingUtil.createBalance(bucket);

        assertNotNull(balance);
        assertEquals(String.valueOf(bucket.getBucketId()), balance.getBucketId());
        assertEquals(bucket.getExpiryDate(), balance.getServiceExpiry());
        assertEquals(bucket.getPriority(), balance.getPriority());
        assertEquals(bucket.getCurrentBalance(), balance.getQuota());
        assertEquals(bucket.getInitialBalance(), balance.getInitialBalance());
        assertEquals(bucket.getServiceStartDate(), balance.getServiceStartDate());
        assertEquals(String.valueOf(bucket.getServiceId()), balance.getServiceId());
        assertEquals(bucket.getStatus(), balance.getServiceStatus());
        assertEquals(bucket.getConsumptionLimit(), balance.getConsumptionLimit());
        assertEquals(bucket.getTimeWindow(), balance.getTimeWindow());
        assertEquals(bucket.getConsumptionTimeWindow(), balance.getConsumptionLimitWindow());
        assertEquals(bucket.getBucketUser(), balance.getBucketUsername());
        assertEquals(bucket.getBucketExpiryDate(), balance.getBucketExpiryDate());
        assertEquals(bucket.isGroup(), balance.isGroup());
        assertEquals(bucket.isUnlimited(), balance.isUnlimited());
        assertEquals(bucket.getUsage(), balance.getUsage());
    }

    @Test
    void testCreateDBWriteRequest_RegularBalance() {
        Balance balance = createSampleBalance(false);
        String userName = "testuser";
        String sessionId = "session-123";
        EventType eventType = EventType.UPDATE_EVENT;

        DBWriteRequest request = MappingUtil.createDBWriteRequest(balance, userName, sessionId, eventType);

        assertNotNull(request);
        assertEquals(sessionId, request.getSessionId());
        assertEquals(userName, request.getUserName());
        assertEquals(eventType, request.getEventType());
        assertEquals(AppConstant.BUCKET_INSTANCE_TABLE, request.getTableName());
        assertNotNull(request.getEventId());
        assertNotNull(request.getTimestamp());

        Map<String, Object> whereConditions = request.getWhereConditions();
        assertEquals(balance.getServiceId(), whereConditions.get(AppConstant.SERVICE_ID));
        assertEquals(balance.getBucketId(), whereConditions.get(AppConstant.ID));

        Map<String, Object> columnValues = request.getColumnValues();
        long expectedUsage = balance.getInitialBalance() - balance.getQuota();
        assertEquals(balance.getQuota(), columnValues.get(AppConstant.CURRENT_BALANCE));
        assertEquals(expectedUsage, columnValues.get(AppConstant.USAGE));
        assertNotNull(columnValues.get(AppConstant.UPDATED_AT));
    }

    @Test
    void testCreateDBWriteRequest_UnlimitedBalance() {
        Balance balance = createSampleBalance(true);
        balance.setUsage(5000L);
        String userName = "testuser";
        String sessionId = "session-123";
        EventType eventType = EventType.CREATE_EVENT;

        DBWriteRequest request = MappingUtil.createDBWriteRequest(balance, userName, sessionId, eventType);

        assertNotNull(request);
        Map<String, Object> columnValues = request.getColumnValues();
        assertEquals(balance.getInitialBalance(), columnValues.get(AppConstant.CURRENT_BALANCE));
        assertEquals(balance.getUsage(), columnValues.get(AppConstant.USAGE));
    }

    private AccountingRequestDto createSampleRequest() {
        return new AccountingRequestDto(
            "testuser",
            "session-123",
            AccountingRequestDto.ActionType.START,
            1000,
            500L,
            500L,
            1,
            1,
            "192.168.1.1",
            "10.0.0.1",
            "nas-123",
            "port-1",
            0,
            LocalDateTime.now()
        );
    }

    private ServiceBucketInfo createSampleBucket() {
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketId(1001L);
        bucket.setServiceId(2001L);
        bucket.setExpiryDate(LocalDateTime.now().plusDays(30));
        bucket.setPriority(1L);
        bucket.setCurrentBalance(1000L);
        bucket.setInitialBalance(2000L);
        bucket.setServiceStartDate(LocalDateTime.now().minusDays(1));
        bucket.setStatus("Active");
        bucket.setConsumptionLimit(500L);
        bucket.setTimeWindow("0-24");
        bucket.setConsumptionTimeWindow(30L);
        bucket.setBucketUser("bucketuser");
        bucket.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        bucket.setGroup(false);
        bucket.setUnlimited(false);
        bucket.setUsage(1000L);
        return bucket;
    }

    private Balance createSampleBalance(boolean unlimited) {
        Balance balance = new Balance();
        balance.setBucketId("1001");
        balance.setServiceId("2001");
        balance.setInitialBalance(2000L);
        balance.setQuota(1000L);
        balance.setUnlimited(unlimited);
        return balance;
    }
}
