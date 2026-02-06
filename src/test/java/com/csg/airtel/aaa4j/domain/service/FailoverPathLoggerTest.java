package com.csg.airtel.aaa4j.domain.service;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FailoverPathLoggerTest {

    @Mock
    private Logger mockLogger;

    @Test
    @DisplayName("Should log error message with correct format when fallback is activated")
    void testLogFallbackPath() {
        // Arrange
        String operation = "ProcessPayment";
        String sessionId = "12345-ABC";
        String errorMessage = "Connection Timeout";
        Throwable reason = new RuntimeException(errorMessage);

        // Act
        FailoverPathLogger.logFallbackPath(mockLogger, operation, sessionId, reason);

        // Assert
        // Verify that errorf was called with the specific format string and arguments
        verify(mockLogger).errorf(
                eq("Fallback path activated for operation [%s], sessionId [%s]: %s"),
                eq(operation),
                eq(sessionId),
                eq(errorMessage)
        );
    }
}