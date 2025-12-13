package com.csg.airtel.aaa4j.domain.model.cdr;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingCDREvent {

    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("eventVersion")
    private String eventVersion;

    @JsonProperty("eventTimestamp")
    private Instant eventTimestamp;

    @JsonProperty("source")
    private String source;


    @JsonProperty("payload")
    private Payload payload;
}
