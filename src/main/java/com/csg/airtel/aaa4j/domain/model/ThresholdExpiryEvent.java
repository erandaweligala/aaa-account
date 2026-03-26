package com.csg.airtel.aaa4j.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record ThresholdExpiryEvent(
        Meta meta,
        Data data
) {

    public record Meta(
            String eventId,
            String source,
            String eventType,
            Instant messageTimeStamp,
            String productType
    ) {}

    public record Data(
            String emailId,
            String contactNumber,
            String message,
            String serviceLineNumber,
            long availableQuota,
            String planDescription,
            int thresholdLevel,
            long initialBalance,
            String planCode,
            @JsonProperty("expiryDate") Instant expiryDate
    ) {}
}