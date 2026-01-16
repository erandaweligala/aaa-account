package com.csg.airtel.aaa4j.domain.model.coa;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CoA (Change of Authorization) Disconnect Request DTO
 * Minimal request payload for HTTP-based RADIUS disconnect
 */
public record CoADisconnectRequest(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("userName") String userName,
        @JsonProperty("nasIp") String nasIp,
        @JsonProperty("framedIp") String framedIp
) {
    /**
     * Factory method to create CoA disconnect request
     */
    public static CoADisconnectRequest of(String sessionId, String userName, String nasIp, String framedIp) {
        return new CoADisconnectRequest(sessionId, userName, nasIp, framedIp);
    }
}
