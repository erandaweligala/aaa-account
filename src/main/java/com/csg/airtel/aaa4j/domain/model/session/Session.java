package com.csg.airtel.aaa4j.domain.model.session;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Session {
    private String sessionId;
    private LocalDateTime sessionInitiatedTime;
    private String previousUsageBucketId;
    private Integer sessionTime;
    private Long previousTotalUsageQuotaValue;
    private String framedId;
    private String nasIp;
    private String nasPortId;
    private boolean isNewSession;
    private long availableBalance;
    private String groupId;
    private String userName;

}
