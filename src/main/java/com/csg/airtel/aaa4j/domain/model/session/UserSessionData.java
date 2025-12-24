package com.csg.airtel.aaa4j.domain.model.session;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class UserSessionData {
    private String sessionTimeOut;
    private String status; //if bard user consume with global plan
    private String userName;
    private String groupId;
    private long concurrency;
    private String templateIds; // "ex-: 1,3,5"
    private List<Balance> balance = new ArrayList<>();
    private List<Session> sessions = new ArrayList<>();
    private QosParam qosParam;

}
