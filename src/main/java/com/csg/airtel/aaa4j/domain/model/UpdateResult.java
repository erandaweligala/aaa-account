package com.csg.airtel.aaa4j.domain.model;


import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;


public record UpdateResult(
        boolean success,
        String errorMessage,
        Long newQuota,
        Balance balance,
        String bucketId, String previousUsageBucketId, Session sessionData) {

    public static UpdateResult success(Long newQuota,String bucketId,Balance balance,String previousUsageBucketId,Session sessionData) {
        return new UpdateResult(true, null, newQuota,balance, bucketId,previousUsageBucketId,sessionData);
    }

   public static UpdateResult failure(String errorMessage,Session sessionData) {
        return new UpdateResult(false, errorMessage, null,null, null,null,sessionData);
    }

    public static UpdateResult skipped(String reason) {
        return new UpdateResult(false, reason, null, null, null, null, null);
    }

}