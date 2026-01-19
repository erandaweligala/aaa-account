package com.csg.airtel.aaa4j.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;

public record AccountingResponseEvent(
        @JsonProperty("eventType") EventType eventType,
        @JsonProperty("eventTime") LocalDateTime eventTime,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("action") ResponseAction action,
        @JsonProperty("message") String message,
        @JsonProperty("totalQuotaBalance") Long totalQuotaBalance,
        @JsonProperty("qosParameters") Map<String,String> qosParameters
) {
    public enum ResponseAction {
        DISCONNECT,
        FUP_APPLY,
        PACKAGE_UPGRADE,
        INTERNAL_ERROR,
        IGNORE_PROCESSING,
        SUCCESS

    }
    public enum EventType {
       COA
    }
}