package com.csg.airtel.aaa4j.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AccountingRequestDtoTest {

    @Test
    void testRecordCreation() {
        String eventId = "EVT001";
        String sessionId = "SES001";
        String nasIP = "192.168.1.1";
        String username = "testuser";
        AccountingRequestDto.ActionType actionType = AccountingRequestDto.ActionType.START;
        Integer inputOctets = 1024;
        Integer outputOctets = 2048;
        Integer sessionTime = 3600;
        Instant timestamp = Instant.now();
        String nasPortId = "PORT001";
        String framedIPAddress = "10.0.0.1";
        Integer delayTime = 100;
        Integer inputGigaWords = 0;
        Integer outputGigaWords = 0;
        String nasIdentifier = "NAS001";

        AccountingRequestDto dto = new AccountingRequestDto(
                eventId, sessionId, nasIP, username, actionType,
                inputOctets, outputOctets, sessionTime, timestamp,
                nasPortId, framedIPAddress, delayTime,
                inputGigaWords, outputGigaWords, nasIdentifier
        );

        assertEquals(eventId, dto.eventId());
        assertEquals(sessionId, dto.sessionId());
        assertEquals(nasIP, dto.nasIP());
        assertEquals(username, dto.username());
        assertEquals(actionType, dto.actionType());
        assertEquals(inputOctets, dto.inputOctets());
        assertEquals(outputOctets, dto.outputOctets());
        assertEquals(sessionTime, dto.sessionTime());
        assertEquals(timestamp, dto.timestamp());
        assertEquals(nasPortId, dto.nasPortId());
        assertEquals(framedIPAddress, dto.framedIPAddress());
        assertEquals(delayTime, dto.delayTime());
        assertEquals(inputGigaWords, dto.inputGigaWords());
        assertEquals(outputGigaWords, dto.outputGigaWords());
        assertEquals(nasIdentifier, dto.nasIdentifier());
    }

    @Test
    void testActionTypeStart() {
        AccountingRequestDto dto = new AccountingRequestDto(
                "EVT001", "SES001", "192.168.1.1", "user1",
                AccountingRequestDto.ActionType.START,
                0, 0, 0, Instant.now(), "PORT1", "10.0.0.1",
                0, 0, 0, "NAS1"
        );

        assertEquals(AccountingRequestDto.ActionType.START, dto.actionType());
    }

    @Test
    void testActionTypeInterimUpdate() {
        AccountingRequestDto dto = new AccountingRequestDto(
                "EVT002", "SES002", "192.168.1.2", "user2",
                AccountingRequestDto.ActionType.INTERIM_UPDATE,
                1024, 2048, 1800, Instant.now(), "PORT2", "10.0.0.2",
                50, 1, 1, "NAS2"
        );

        assertEquals(AccountingRequestDto.ActionType.INTERIM_UPDATE, dto.actionType());
    }

    @Test
    void testActionTypeStop() {
        AccountingRequestDto dto = new AccountingRequestDto(
                "EVT003", "SES003", "192.168.1.3", "user3",
                AccountingRequestDto.ActionType.STOP,
                5000, 10000, 7200, Instant.now(), "PORT3", "10.0.0.3",
                200, 2, 3, "NAS3"
        );

        assertEquals(AccountingRequestDto.ActionType.STOP, dto.actionType());
    }

    @Test
    void testRecordEquality() {
        Instant now = Instant.now();
        AccountingRequestDto dto1 = new AccountingRequestDto(
                "EVT001", "SES001", "192.168.1.1", "user1",
                AccountingRequestDto.ActionType.START,
                0, 0, 0, now, "PORT1", "10.0.0.1",
                0, 0, 0, "NAS1"
        );

        AccountingRequestDto dto2 = new AccountingRequestDto(
                "EVT001", "SES001", "192.168.1.1", "user1",
                AccountingRequestDto.ActionType.START,
                0, 0, 0, now, "PORT1", "10.0.0.1",
                0, 0, 0, "NAS1"
        );

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    @Test
    void testRecordToString() {
        AccountingRequestDto dto = new AccountingRequestDto(
                "EVT001", "SES001", "192.168.1.1", "user1",
                AccountingRequestDto.ActionType.START,
                0, 0, 0, Instant.now(), "PORT1", "10.0.0.1",
                0, 0, 0, "NAS1"
        );

        String result = dto.toString();
        assertNotNull(result);
        assertTrue(result.contains("EVT001"));
        assertTrue(result.contains("SES001"));
    }

    @Test
    void testActionTypeEnum() {
        assertEquals(3, AccountingRequestDto.ActionType.values().length);
        assertEquals(AccountingRequestDto.ActionType.START,
                     AccountingRequestDto.ActionType.valueOf("START"));
        assertEquals(AccountingRequestDto.ActionType.INTERIM_UPDATE,
                     AccountingRequestDto.ActionType.valueOf("INTERIM_UPDATE"));
        assertEquals(AccountingRequestDto.ActionType.STOP,
                     AccountingRequestDto.ActionType.valueOf("STOP"));
    }

    @Test
    void testNullValues() {
        AccountingRequestDto dto = new AccountingRequestDto(
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null
        );

        assertNull(dto.eventId());
        assertNull(dto.sessionId());
        assertNull(dto.actionType());
    }
}
