package com.csg.airtel.aaa4j.exception;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseExceptionTest {

    @Test
    void testConstructor() {
        String message = "Test error message";
        String description = "CONTROLLER";
        Response.Status httpStatus = Response.Status.BAD_REQUEST;
        String responseCode = "E1000";
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        BaseException exception = new BaseException(
                message, description, httpStatus, responseCode, stackTrace
        );

        assertEquals(message, exception.getMessage());
        assertEquals(description, exception.getDescription());
        assertEquals(httpStatus, exception.getHttpStatus());
        assertEquals(responseCode, exception.getResponseCode());
        assertEquals(stackTrace, exception.getStackTraceElements());
    }

    @Test
    void testGetMessage() {
        String message = "Database connection failed";
        BaseException exception = new BaseException(
                message, "SERVICE", Response.Status.INTERNAL_SERVER_ERROR, "E1001", new StackTraceElement[0]
        );

        assertEquals(message, exception.getMessage());
    }

    @Test
    void testGetDescription() {
        String description = "SERVICE_LAYER";
        BaseException exception = new BaseException(
                "Error", description, Response.Status.INTERNAL_SERVER_ERROR, "E1001", new StackTraceElement[0]
        );

        assertEquals(description, exception.getDescription());
    }

    @Test
    void testGetHttpStatus() {
        Response.Status status = Response.Status.NOT_FOUND;
        BaseException exception = new BaseException(
                "Not found", "CONTROLLER", status, "E1000", new StackTraceElement[0]
        );

        assertEquals(status, exception.getHttpStatus());
        assertEquals(404, exception.getHttpStatus().getStatusCode());
    }

    @Test
    void testGetResponseCode() {
        String responseCode = "E1003";
        BaseException exception = new BaseException(
                "Cache error", "CACHE", Response.Status.SERVICE_UNAVAILABLE, responseCode, new StackTraceElement[0]
        );

        assertEquals(responseCode, exception.getResponseCode());
    }

    @Test
    void testGetStackTraceElements() {
        StackTraceElement[] stackTrace = new StackTraceElement[]{
                new StackTraceElement("TestClass", "testMethod", "TestClass.java", 10)
        };

        BaseException exception = new BaseException(
                "Error", "TEST", Response.Status.INTERNAL_SERVER_ERROR, "E1000", stackTrace
        );

        assertEquals(stackTrace, exception.getStackTraceElements());
        assertEquals(1, exception.getStackTraceElements().length);
    }

    @Test
    void testToString() {
        String message = "Test error";
        String description = "CONTROLLER";
        Response.Status httpStatus = Response.Status.BAD_REQUEST;
        String responseCode = "E1000";

        BaseException exception = new BaseException(
                message, description, httpStatus, responseCode, new StackTraceElement[0]
        );

        String result = exception.toString();

        assertNotNull(result);
        assertTrue(result.contains("BaseException"));
        assertTrue(result.contains(message));
        assertTrue(result.contains(description));
        assertTrue(result.contains(responseCode));
    }

    @Test
    void testIsRuntimeException() {
        BaseException exception = new BaseException(
                "Error", "TEST", Response.Status.INTERNAL_SERVER_ERROR, "E1000", new StackTraceElement[0]
        );

        assertTrue(exception instanceof RuntimeException);
    }

    @Test
    void testWithDifferentHttpStatuses() {
        BaseException badRequest = new BaseException(
                "Bad request", "CONTROLLER", Response.Status.BAD_REQUEST, "E1000", new StackTraceElement[0]
        );
        assertEquals(Response.Status.BAD_REQUEST, badRequest.getHttpStatus());

        BaseException unauthorized = new BaseException(
                "Unauthorized", "CONTROLLER", Response.Status.UNAUTHORIZED, "E1000", new StackTraceElement[0]
        );
        assertEquals(Response.Status.UNAUTHORIZED, unauthorized.getHttpStatus());

        BaseException serverError = new BaseException(
                "Server error", "SERVICE", Response.Status.INTERNAL_SERVER_ERROR, "E1001", new StackTraceElement[0]
        );
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, serverError.getHttpStatus());
    }

    @Test
    void testWithEmptyStackTrace() {
        BaseException exception = new BaseException(
                "Error", "TEST", Response.Status.INTERNAL_SERVER_ERROR, "E1000", new StackTraceElement[0]
        );

        assertNotNull(exception.getStackTraceElements());
        assertEquals(0, exception.getStackTraceElements().length);
    }

    @Test
    void testWithNullMessage() {
        BaseException exception = new BaseException(
                null, "TEST", Response.Status.INTERNAL_SERVER_ERROR, "E1000", new StackTraceElement[0]
        );

        assertNull(exception.getMessage());
    }
}
