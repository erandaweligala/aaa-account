package com.csg.airtel.aaa4j.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDateTime;

public record ThresholdExpiryEvent(
        Meta meta,
        Data data
) {

    public record Meta(
            String eventId,
            String source,
            String eventType,//"THREHOLD/EXPIRY"
            Instant messageTimeStamp,
            String productType //FTTX
    ) {}

    public record Data(
            String message,
            String serviceLineNumber,
            long availableQuota,
            String planDescription,
            int thresholdLevel,
            long initialBalance,
            String planCode,
            @JsonProperty("expiryDate") LocalDateTime expiryDate
    ) {}
}