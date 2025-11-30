package com.csg.airtel.aaa4j.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DBWriteRequestTest {

    private DBWriteRequest request;

    @BeforeEach
    void setUp() {
        request = new DBWriteRequest();
    }

    @Test
    void testSetAndGetEventId() {
        String eventId = "EVT001";
        request.setEventId(eventId);
        assertEquals(eventId, request.getEventId());
    }

    @Test
    void testSetAndGetEventType() {
        EventType eventType = EventType.CREATE_EVENT;
        request.setEventType(eventType);
        assertEquals(eventType, request.getEventType());
    }

    @Test
    void testSetAndGetTimestamp() {
        LocalDateTime timestamp = LocalDateTime.now();
        request.setTimestamp(timestamp);
        assertEquals(timestamp, request.getTimestamp());
    }

    @Test
    void testSetAndGetUserName() {
        String userName = "testuser";
        request.setUserName(userName);
        assertEquals(userName, request.getUserName());
    }

    @Test
    void testSetAndGetSessionId() {
        String sessionId = "SES001";
        request.setSessionId(sessionId);
        assertEquals(sessionId, request.getSessionId());
    }

    @Test
    void testSetAndGetColumnValues() {
        Map<String, Object> columnValues = new HashMap<>();
        columnValues.put("col1", "value1");
        columnValues.put("col2", 123);

        request.setColumnValues(columnValues);
        assertEquals(columnValues, request.getColumnValues());
        assertEquals("value1", request.getColumnValues().get("col1"));
        assertEquals(123, request.getColumnValues().get("col2"));
    }

    @Test
    void testSetAndGetWhereConditions() {
        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("id", 1);
        whereConditions.put("status", "ACTIVE");

        request.setWhereConditions(whereConditions);
        assertEquals(whereConditions, request.getWhereConditions());
        assertEquals(1, request.getWhereConditions().get("id"));
    }

    @Test
    void testSetAndGetTableName() {
        String tableName = "users";
        request.setTableName(tableName);
        assertEquals(tableName, request.getTableName());
    }

    @Test
    void testCompleteRequest() {
        String eventId = "EVT001";
        EventType eventType = EventType.UPDATE_EVENT;
        LocalDateTime timestamp = LocalDateTime.now();
        String userName = "john.doe";
        String sessionId = "SES123";
        String tableName = "sessions";

        Map<String, Object> columnValues = new HashMap<>();
        columnValues.put("status", "ACTIVE");

        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("id", 1);

        request.setEventId(eventId);
        request.setEventType(eventType);
        request.setTimestamp(timestamp);
        request.setUserName(userName);
        request.setSessionId(sessionId);
        request.setTableName(tableName);
        request.setColumnValues(columnValues);
        request.setWhereConditions(whereConditions);

        assertEquals(eventId, request.getEventId());
        assertEquals(eventType, request.getEventType());
        assertEquals(timestamp, request.getTimestamp());
        assertEquals(userName, request.getUserName());
        assertEquals(sessionId, request.getSessionId());
        assertEquals(tableName, request.getTableName());
        assertEquals(columnValues, request.getColumnValues());
        assertEquals(whereConditions, request.getWhereConditions());
    }

    @Test
    void testNullValues() {
        request.setEventId(null);
        request.setEventType(null);
        request.setTimestamp(null);
        request.setUserName(null);
        request.setSessionId(null);
        request.setTableName(null);
        request.setColumnValues(null);
        request.setWhereConditions(null);

        assertNull(request.getEventId());
        assertNull(request.getEventType());
        assertNull(request.getTimestamp());
        assertNull(request.getUserName());
        assertNull(request.getSessionId());
        assertNull(request.getTableName());
        assertNull(request.getColumnValues());
        assertNull(request.getWhereConditions());
    }

    @Test
    void testEmptyMaps() {
        Map<String, Object> emptyMap = new HashMap<>();
        request.setColumnValues(emptyMap);
        request.setWhereConditions(emptyMap);

        assertNotNull(request.getColumnValues());
        assertNotNull(request.getWhereConditions());
        assertTrue(request.getColumnValues().isEmpty());
        assertTrue(request.getWhereConditions().isEmpty());
    }
}
