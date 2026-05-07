package com.csg.airtel.aaa4j.domain.model.coa;

/**
 * Business scenarios that trigger a COA-Disconnect request.
 * Used as a Prometheus label so each scenario can be counted independently.
 */
public enum CoaDisconnectScenario {
    DATA_BALANCE_EXHAUSTED,
    NO_VALID_BALANCE,
    MAX_CONCURRENT_SESSIONS,
    NO_SERVICE_BUCKETS,
    ZERO_DATA_QUOTA,
    BALANCE_PRIORITY_CHANGE,
    BALANCE_EXPIRY_CHANGED,
    MANUAL_TERMINATION,
    USER_STATUS_CHANGED,
    SERVICE_STATUS_CHANGED,
    SERVICE_DELETED,
    CONSUMPTION_LIMIT_EXCEEDED,
    BUCKET_CHANGE_OR_QUOTA_EXHAUSTED,
    ABSOLUTE_SESSION_TIMEOUT;

    public String label() {
        return name().toLowerCase();
    }
}
