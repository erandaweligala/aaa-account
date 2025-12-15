package com.csg.airtel.aaa4j.domain.model.cdr;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Coa {

    @JsonProperty("coaType")
    private String coaType;

    @JsonProperty("coaCode")
    private Integer coaCode;

    @JsonProperty("destinationPort")
    private Integer destinationPort;
}
