package com.csg.airtel.aaa4j.domain.model.cdr;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Network {

    @JsonProperty("framedIpAddress")
    private String framedIpAddress;

    @JsonProperty("framedProtocol")
    private String framedProtocol;

    @JsonProperty("serviceType")
    private String serviceType;

    @JsonProperty("calledStationId")
    private String calledStationId;
}
