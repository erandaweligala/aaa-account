package com.csg.airtel.aaa4j.domain.model.cdr;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Accounting {

    @JsonProperty("acctStatusType")
    private String acctStatusType;

    @JsonProperty("acctSessionTime")
    private Integer acctSessionTime;

    @JsonProperty("acctInputOctets")
    private Long acctInputOctets;

    @JsonProperty("acctOutputOctets")
    private Long acctOutputOctets;

    @JsonProperty("acctInputPackets")
    private Integer acctInputPackets;

    @JsonProperty("acctOutputPackets")
    private Integer acctOutputPackets;

    @JsonProperty("acctInputGigawords")
    private Integer acctInputGigawords;

    @JsonProperty("acctOutputGigawords")
    private Integer acctOutputGigawords;

    @JsonProperty("totalUsage")
    private Long totalUsage;
}
