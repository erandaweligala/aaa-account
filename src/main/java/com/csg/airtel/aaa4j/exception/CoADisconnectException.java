package com.csg.airtel.aaa4j.exception;

import com.csg.airtel.aaa4j.domain.constant.ResponseCodeEnum;
import jakarta.ws.rs.core.Response;

/**
 * Exception thrown when CoA disconnect request fails.
 * Extends BaseException to leverage standardized exception handling.
 */
public class CoADisconnectException extends BaseException {

    private static final String DESCRIPTION = "CoA Client Layer";

    /**
     * Constructs a new CoADisconnectException.
     *
     * @param message       the error message
     * @param httpStatus    the HTTP status returned by the NAS server
     * @param cause         the underlying cause of the disconnect failure
     */
    public CoADisconnectException(String message, Response.Status httpStatus, Throwable cause) {
        super(
            message,
            DESCRIPTION,
            httpStatus,
            ResponseCodeEnum.COA_DISCONNECT_ERROR.code(),
            cause != null ? cause.getStackTrace() : new StackTraceElement[0]
        );
        if (cause != null) {
            initCause(cause);
        }
    }

    /**
     * Constructs a new CoADisconnectException without a cause.
     *
     * @param message       the error message
     * @param httpStatus    the HTTP status returned by the NAS server
     */
    public CoADisconnectException(String message, Response.Status httpStatus) {
        this(message, httpStatus, null);
    }
}
