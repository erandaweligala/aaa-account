package com.csg.airtel.aaa4j.domain.model.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@RegisterForReflection
public class UserSessionData {
    private String sessionTimeOut;
    private String userStatus; //if bard user consume with global plan
    private String userName;
    private String groupId;
    private long concurrency;
    private long superTemplateId; // "ex-: 1,3,5"
    private List<Balance> balance = new ArrayList<>();
    private List<Session> sessions = new ArrayList<>();
    private QosParam qosParam;

}
