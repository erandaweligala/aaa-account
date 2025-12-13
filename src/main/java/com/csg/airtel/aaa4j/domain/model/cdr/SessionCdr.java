package com.csg.airtel.aaa4j.domain.model.cdr;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionCdr {

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("sessionTime")
    private String sessionTime;

    @JsonProperty("startTime")
    private Instant startTime;

    @JsonProperty("updateTime")
    private Instant updateTime;

    @JsonProperty("nasIdentifier")
    private String nasIdentifier;

    @JsonProperty("nasIpAddress")
    private String nasIpAddress;

    @JsonProperty("nasPort")
    private String nasPort;

    @JsonProperty("nasPortType")
    private String nasPortType;

    @JsonProperty("sessionStopTime")
    private Instant sessionStopTime;
}
