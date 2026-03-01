package com.csg.airtel.aaa4j.exception;

import com.csg.airtel.aaa4j.domain.constant.ResponseCodeEnum;
import jakarta.ws.rs.core.Response;

/**
 * Exception thrown when CoA disconnect request fails.
 * Extends BaseException to leverage standardized exception handling.
 * Supports retryable flag for transient 5xx errors at high TPS.
 */
public class CoADisconnectException extends BaseException {

    private static final String DESCRIPTION = "CoA Client Layer";
    private final boolean retryable;

    /**
     * Constructs a new CoADisconnectException.
     *
     * @param message       the error message
     * @param httpStatus    the HTTP status returned by the NAS server
     * @param cause         the underlying cause of the disconnect failure
     * @param retryable     whether this error is transient and should be retried
     */
    public CoADisconnectException(String message, Response.Status httpStatus, Throwable cause, boolean retryable) {
        super(
            message,
            DESCRIPTION,
            httpStatus,
            ResponseCodeEnum.COA_DISCONNECT_ERROR.code(),
            cause != null ? cause.getStackTrace() : new StackTraceElement[0]
        );
        this.retryable = retryable;
        if (cause != null) {
            initCause(cause);
        }
    }

    /**
     * Constructs a new CoADisconnectException with retryable flag, without a cause.
     *
     * @param message       the error message
     * @param httpStatus    the HTTP status returned by the NAS server
     * @param retryable     whether this error is transient and should be retried
     */
    public CoADisconnectException(String message, Response.Status httpStatus, boolean retryable) {
        this(message, httpStatus, null, retryable);
    }

    /**
     * Constructs a new CoADisconnectException (non-retryable by default).
     *
     * @param message       the error message
     * @param httpStatus    the HTTP status returned by the NAS server
     * @param cause         the underlying cause of the disconnect failure
     */
    public CoADisconnectException(String message, Response.Status httpStatus, Throwable cause) {
        this(message, httpStatus, cause, false);
    }

    /**
     * Constructs a new CoADisconnectException without a cause (non-retryable by default).
     *
     * @param message       the error message
     * @param httpStatus    the HTTP status returned by the NAS server
     */
    public CoADisconnectException(String message, Response.Status httpStatus) {
        this(message, httpStatus, null, false);
    }

    public boolean isRetryableError() {
        return retryable;
    }

    /**
     * Static predicate for use with Mutiny retry filters.
     * Returns true if the throwable is a retryable CoADisconnectException.
     */
    public static boolean isRetryable(Throwable t) {
        return t instanceof CoADisconnectException cde && cde.isRetryableError();
    }
}
