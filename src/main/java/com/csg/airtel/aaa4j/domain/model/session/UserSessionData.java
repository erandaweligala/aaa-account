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
    private String userName;
    private String groupId;
    private List<Balance> balance;
    private List<Session> sessions = new ArrayList<>();
    private QosParam qosParam;

}
