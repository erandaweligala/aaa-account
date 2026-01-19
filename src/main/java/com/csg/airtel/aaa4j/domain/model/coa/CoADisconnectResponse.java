package com.csg.airtel.aaa4j.domain.model.coa;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CoA (Change of Authorization) Disconnect Response DTO
 * Minimal response from NAS server for disconnect acknowledgment
 */
public record CoADisconnectResponse(
        @JsonProperty("status") String status,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("message") String message
) {
    /**
     * Check if the disconnect was acknowledged successfully
     */
    public boolean isAck() {
        return "ACK".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status);
    }

    /**
     * Check if the disconnect was rejected
     */
    public boolean isNack() {
        return "NAK".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }
}
