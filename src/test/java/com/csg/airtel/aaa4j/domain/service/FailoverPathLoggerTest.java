package com.csg.airtel.aaa4j.domain.service;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailoverPathLoggerTest {

    @Test
    void testLogFallbackPath() {
        Logger mockLogger = mock(Logger.class);
        String operation = "testOperation";
        String sessionId = "session-123";
        Throwable reason = new RuntimeException("Test error");

        FailoverPathLogger.logFallbackPath(mockLogger, operation, sessionId, reason);

        ArgumentCaptor<String> formatCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> arg1Captor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg2Captor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg3Captor = ArgumentCaptor.forClass(Object.class);

        verify(mockLogger).errorf(
            formatCaptor.capture(),
            arg1Captor.capture(),
            arg2Captor.capture(),
            arg3Captor.capture()
        );

        String capturedFormat = formatCaptor.getValue();
        assertTrue(capturedFormat.contains("Fallback path activated"));
        assertTrue(capturedFormat.contains("%s"));
        assertEquals(operation, arg1Captor.getValue());
        assertEquals(sessionId, arg2Captor.getValue());
        assertEquals("Test error", arg3Captor.getValue());
    }

    @Test
    void testLogFallbackPath_WithNullMessage() {
        Logger mockLogger = mock(Logger.class);
        String operation = "testOperation";
        String sessionId = "session-456";
        Throwable reason = new RuntimeException();

        FailoverPathLogger.logFallbackPath(mockLogger, operation, sessionId, reason);

        verify(mockLogger, times(1)).errorf(anyString(), any(), any(), any());
    }
}
