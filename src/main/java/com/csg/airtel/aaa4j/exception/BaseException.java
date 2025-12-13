package com.csg.airtel.aaa4j.exception;

import jakarta.ws.rs.core.Response;

import java.util.Arrays;

/**
 * Base exception class for handling custom application-level exceptions in Quarkus.
 * Encapsulates error details including message, description, HTTP status, response code, and stack trace.
 */
public class BaseException extends RuntimeException {

    private final String description;
    private final Response.Status httpStatus;
    private final String responseCode;
    private final StackTraceElement[] stackTraceElements;

    /**
     * Constructs a new BaseException.
     *
     * @param message             the error message
     * @param description               the application description where the error occurred (e.g., CONTROLLER, SERVICE)
     * @param httpStatus          the corresponding HTTP status
     * @param responseCode        a predefined response code from ResponseCodeEnum
     * @param stackTraceElements  the captured stack trace of the original exception
     */
    public BaseException(String message, String description, Response.Status httpStatus, String responseCode, StackTraceElement[] stackTraceElements) {
        super(message);
        this.description = description;
        this.httpStatus = httpStatus;
        this.responseCode = responseCode;
        this.stackTraceElements = stackTraceElements;
    }

    public String getDescription() {
        return description;
    }

    public Response.Status getHttpStatus() {
        return httpStatus;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public StackTraceElement[] getStackTraceElements() {
        return stackTraceElements;
    }

    @Override
    public String toString() {
        return "BaseException{" +
                "message='" + getMessage() + '\'' +
                ", description='" + description + '\'' +
                ", httpStatus=" + httpStatus +
                ", responseCode='" + responseCode + '\'' +
                ", stackTrace=" + Arrays.toString(stackTraceElements) +
                '}';
    }
}
