package com.csg.airtel.aaa4j.domain.model;

import com.csg.airtel.aaa4j.domain.model.session.Balance;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BalanceUpdateContext {
    final String previousUsageBucketId;
    final boolean bucketChanged;
    final Balance effectiveBalance;
    final long usageDelta;

    public BalanceUpdateContext(String previousUsageBucketId, boolean bucketChanged, Balance effectiveBalance, long usageDelta) {
        this.previousUsageBucketId = previousUsageBucketId;
        this.bucketChanged = bucketChanged;
        this.effectiveBalance = effectiveBalance;
        this.usageDelta = usageDelta;
    }
}
