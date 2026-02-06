package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.*;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;


import static org.junit.jupiter.api.Assertions.*;


class MappingUtilTest {

    @Test
    @DisplayName("Should create AccountingResponseEvent for COA Event Type")
    void createResponse_COA_ReturnsPopulatedAttributes() {
        AccountingRequestDto    request = new AccountingRequestDto(
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

        AccountingResponseEvent response = MappingUtil.createResponse(
                request, "Success",
                AccountingResponseEvent.EventType.COA,
                AccountingResponseEvent.ResponseAction.DISCONNECT
        );

        assertNotNull(response);
        assertEquals("sess-001", response.sessionId());
    }


    @Test
    @DisplayName("Should create COA Response with specific parameters")
    void createResponse_OverloadedMethod_ReturnsValidEvent() {
        AccountingResponseEvent response = MappingUtil.createResponse(
                "sess-002", "Msg", "10.0.0.2", "192.168.1.2", "user456"
        );

        assertEquals(AccountingResponseEvent.EventType.COA, response.eventType());
        assertEquals(AccountingResponseEvent.ResponseAction.DISCONNECT, response.action());
    }

    @Test
    @DisplayName("Should create COA Response with explicit action parameter")
    void createResponse_WithAction_ReturnsEventWithSpecifiedAction() {
        AccountingResponseEvent response = MappingUtil.createResponse(
                "sess-003", "FUP applied", "10.0.0.3", "192.168.1.3", "user789",
                AccountingResponseEvent.ResponseAction.FUP_APPLY
        );

        assertEquals(AccountingResponseEvent.EventType.COA, response.eventType());
        assertEquals(AccountingResponseEvent.ResponseAction.FUP_APPLY, response.action());
        assertEquals("sess-003", response.sessionId());
        assertEquals("FUP applied", response.message());
    }

    @Test
    @DisplayName("Should map ServiceBucketInfo to Balance correctly")
    void createBalance_MapsAllFields() {
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setBucketId(100L);
        bucket.setExpiryDate(LocalDateTime.now());
        bucket.setPriority(1);
        bucket.setCurrentBalance(500L);
        bucket.setInitialBalance(1000L);
        bucket.setServiceId(50L);
        bucket.setStatus("ACTIVE");
        bucket.setBucketUser("testUser");
        bucket.setGroup(true);
        bucket.setUnlimited(false);
        bucket.setUsage(100L);

        Balance balance = MappingUtil.createBalance(bucket);

        assertEquals("100", balance.getBucketId());
        assertEquals("50", balance.getServiceId());
        assertEquals(500L, balance.getQuota());
        assertTrue(balance.isGroup());
        assertFalse(balance.isUnlimited());
    }

    @Test
    @DisplayName("Should create DBWriteRequest for Limited Balance (Calculates usage)")
    void createDBWriteRequest_LimitedBalance_CalculatesUsage() {
        Balance balance = new Balance();
        balance.setUnlimited(false);
        balance.setInitialBalance(1000L);
        balance.setQuota(800L); // 200 used
        balance.setServiceId("S1");
        balance.setBucketId("B1");

        DBWriteRequest request = MappingUtil.createDBWriteRequest(
                balance, "user1", "sid1", EventType.UPDATE_EVENT);

        assertEquals(200L, request.getColumnValues().get(AppConstant.USAGE));
        assertEquals(800L, request.getColumnValues().get(AppConstant.CURRENT_BALANCE));
        assertEquals("S1", request.getWhereConditions().get(AppConstant.SERVICE_ID));
    }

    @Test
    @DisplayName("Should create DBWriteRequest for Unlimited Balance (Uses usage field)")
    void createDBWriteRequest_UnlimitedBalance_UsesExistingUsage() {
        Balance balance = new Balance();
        balance.setUnlimited(true);
        balance.setInitialBalance(1000L);
        balance.setUsage(150L);
        balance.setServiceId("S2");
        balance.setBucketId("B2");

        DBWriteRequest request = MappingUtil.createDBWriteRequest(
                balance, "user2", "sid2", EventType.CREATE_EVENT);

        assertEquals(150L, request.getColumnValues().get(AppConstant.USAGE));
        assertEquals(1000L, request.getColumnValues().get(AppConstant.CURRENT_BALANCE));
        assertEquals(EventType.CREATE_EVENT, request.getEventType());
    }
}