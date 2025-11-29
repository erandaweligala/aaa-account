package com.csg.airtel.aaa4j.domain.model;


import com.csg.airtel.aaa4j.domain.model.session.Balance;



public record UpdateResult(
        boolean success,
        String errorMessage,
        Long newQuota,
        Balance balance,
        String bucketId,String previousUsageBucketId) {

    public static UpdateResult success(Long newQuota,String bucketId,Balance balance,String previousUsageBucketId) {
        return new UpdateResult(true, null, newQuota,balance, bucketId,previousUsageBucketId);
    }

   public static UpdateResult failure(String errorMessage) {
        return new UpdateResult(false, errorMessage, null,null, null,null);
    }

}