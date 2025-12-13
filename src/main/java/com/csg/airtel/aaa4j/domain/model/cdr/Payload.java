package com.csg.airtel.aaa4j.domain.model.cdr;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;


@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public  class Payload {

    @JsonProperty("session")
    private SessionCdr session;

    @JsonProperty("user")
    private User user;

    @JsonProperty("network")
    private Network network;

    @JsonProperty("accounting")
    private Accounting accounting;
}

