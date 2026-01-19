package com.csg.airtel.aaa4j.exception;

import com.csg.airtel.aaa4j.domain.constant.ResponseCodeEnum;
import jakarta.ws.rs.core.Response;

/**
 * Exception thrown when parsing CoA response fails.
 * Extends BaseException to leverage standardized exception handling.
 */
public class CoAResponseParsingException extends BaseException {

    private static final String DESCRIPTION = "CoA Client Layer";

    /**
     * Constructs a new CoAResponseParsingException.
     *
     * @param message   the error message
     * @param cause     the underlying cause of the parsing failure
     */
    public CoAResponseParsingException(String message, Throwable cause) {
        super(
            message,
            DESCRIPTION,
            Response.Status.INTERNAL_SERVER_ERROR,
            ResponseCodeEnum.COA_RESPONSE_PARSING_ERROR.code(),
            cause != null ? cause.getStackTrace() : new StackTraceElement[0]
        );
        if (cause != null) {
            initCause(cause);
        }
    }

    /**
     * Constructs a new CoAResponseParsingException without a cause.
     *
     * @param message the error message
     */
    public CoAResponseParsingException(String message) {
        this(message, null);
    }
}
