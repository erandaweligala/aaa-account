package com.csg.airtel.aaa4j.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record AccountingRequestDto(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("nasIP") String nasIP,
        @JsonProperty("username") String username,
        @JsonProperty("actionType") ActionType actionType,
        @JsonProperty("inputOctets") Integer inputOctets,
        @JsonProperty("outputOctets") Integer outputOctets,
        @JsonProperty("sessionTime") Integer sessionTime,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("nasPortId") String nasPortId,
        @JsonProperty("framedIPAddress") String framedIPAddress,
        @JsonProperty("delayTime") Integer delayTime,
        @JsonProperty("inputGigaWords") Integer inputGigaWords,
        @JsonProperty("outputGigaWords") Integer outputGigaWords,
        @JsonProperty("nasIdentifier") String nasIdentifier
) {
    public enum ActionType {
        START,
        INTERIM_UPDATE,
        STOP
    }
}