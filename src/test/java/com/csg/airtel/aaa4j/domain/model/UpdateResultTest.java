package com.csg.airtel.aaa4j.domain.model;

import com.csg.airtel.aaa4j.domain.model.session.Balance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateResultTest {

    @Test
    void testSuccessFactoryMethod() {
        Long quota = 5000L;
        String bucketId = "BUCKET123";
        Balance balance = new Balance();
        balance.setQuota(quota);
        String previousBucketId = "BUCKET122";

        UpdateResult result = UpdateResult.success(quota, bucketId, balance, previousBucketId);

        assertTrue(result.success());
        assertNull(result.errorMessage());
        assertEquals(quota, result.newQuota());
        assertEquals(bucketId, result.bucketId());
        assertEquals(balance, result.balance());
        assertEquals(previousBucketId, result.previousUsageBucketId());
    }

    @Test
    void testFailureFactoryMethod() {
        String errorMessage = "Operation failed";

        UpdateResult result = UpdateResult.failure(errorMessage);

        assertFalse(result.success());
        assertEquals(errorMessage, result.errorMessage());
        assertNull(result.newQuota());
        assertNull(result.balance());
        assertNull(result.bucketId());
        assertNull(result.previousUsageBucketId());
    }

    @Test
    void testRecordCreation() {
        Balance balance = new Balance();
        balance.setQuota(1000L);

        UpdateResult result = new UpdateResult(
                true,
                null,
                1000L,
                balance,
                "BUCKET001",
                "BUCKET000"
        );

        assertTrue(result.success());
        assertNull(result.errorMessage());
        assertEquals(1000L, result.newQuota());
        assertEquals(balance, result.balance());
        assertEquals("BUCKET001", result.bucketId());
        assertEquals("BUCKET000", result.previousUsageBucketId());
    }

    @Test
    void testSuccessWithNullPreviousBucket() {
        Long quota = 3000L;
        String bucketId = "BUCKET456";
        Balance balance = new Balance();

        UpdateResult result = UpdateResult.success(quota, bucketId, balance, null);

        assertTrue(result.success());
        assertEquals(quota, result.newQuota());
        assertEquals(bucketId, result.bucketId());
        assertEquals(balance, result.balance());
        assertNull(result.previousUsageBucketId());
    }

    @Test
    void testSuccessWithZeroQuota() {
        Long quota = 0L;
        String bucketId = "BUCKET789";
        Balance balance = new Balance();
        balance.setQuota(quota);

        UpdateResult result = UpdateResult.success(quota, bucketId, balance, "PREV");

        assertTrue(result.success());
        assertEquals(0L, result.newQuota());
        assertEquals(balance, result.balance());
    }

    @Test
    void testFailureWithEmptyMessage() {
        UpdateResult result = UpdateResult.failure("");

        assertFalse(result.success());
        assertEquals("", result.errorMessage());
        assertNull(result.newQuota());
        assertNull(result.balance());
        assertNull(result.bucketId());
    }

    @Test
    void testRecordEquality() {
        Balance balance = new Balance();
        balance.setQuota(1000L);

        UpdateResult result1 = new UpdateResult(true, null, 1000L, balance, "B1", "B0");
        UpdateResult result2 = new UpdateResult(true, null, 1000L, balance, "B1", "B0");

        assertEquals(result1, result2);
        assertEquals(result1.hashCode(), result2.hashCode());
    }

    @Test
    void testRecordToString() {
        UpdateResult result = UpdateResult.success(1000L, "BUCKET", new Balance(), "PREV");

        String resultString = result.toString();
        assertNotNull(resultString);
        assertTrue(resultString.contains("success=true"));
        assertTrue(resultString.contains("1000"));
    }

    @Test
    void testFailureRecordToString() {
        UpdateResult result = UpdateResult.failure("Error occurred");

        String resultString = result.toString();
        assertNotNull(resultString);
        assertTrue(resultString.contains("success=false"));
        assertTrue(resultString.contains("Error occurred"));
    }
}
