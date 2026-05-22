package com.csg.airtel.aaa4j.domain.model.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@RegisterForReflection
public class QosParam {
    private String normalBandwidth;
    private String fupBandwidth;
}
