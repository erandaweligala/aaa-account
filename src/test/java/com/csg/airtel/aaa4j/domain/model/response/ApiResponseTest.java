package com.csg.airtel.aaa4j.domain.model.response;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    private ApiResponse<String> response;

    @BeforeEach
    void setUp() {
        response = new ApiResponse<>();
    }

    @Test
    void testSetAndGetTimestamp() {
        Instant now = Instant.now();
        response.setTimestamp(now);
        assertEquals(now, response.getTimestamp());
    }

    @Test
    void testSetAndGetMessage() {
        String message = "Success";
        response.setMessage(message);
        assertEquals(message, response.getMessage());
    }

    @Test
    void testSetAndGetData() {
        String data = "Test Data";
        response.setData(data);
        assertEquals(data, response.getData());
    }

    @Test
    void testGenericDataType() {
        ApiResponse<Integer> intResponse = new ApiResponse<>();
        intResponse.setData(42);
        assertEquals(42, intResponse.getData());

        ApiResponse<Boolean> boolResponse = new ApiResponse<>();
        boolResponse.setData(true);
        assertTrue(boolResponse.getData());
    }

    @Test
    void testCompleteResponse() {
        Instant timestamp = Instant.now();
        String message = "Operation successful";
        String data = "Result data";

        response.setTimestamp(timestamp);
        response.setMessage(message);
        response.setData(data);

        assertEquals(timestamp, response.getTimestamp());
        assertEquals(message, response.getMessage());
        assertEquals(data, response.getData());
    }

    @Test
    void testNullValues() {
        response.setTimestamp(null);
        response.setMessage(null);
        response.setData(null);

        assertNull(response.getTimestamp());
        assertNull(response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testErrorResponse() {
        response.setMessage("Error occurred");
        response.setData(null);

        assertEquals("Error occurred", response.getMessage());
        assertNull(response.getData());
    }
}
