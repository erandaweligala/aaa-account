package com.csg.airtel.aaa4j.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Event model for quota threshold notifications.
 * Published when user's quota remaining crosses threshold levels (60%, 70%, 80%).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QuotaThresholdNotification {
    private String message;
    private String username;
    private String type;
    private Long availableQuota;
}
