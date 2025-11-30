package com.csg.airtel.aaa4j.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ServiceBucketInfoTest {

    private ServiceBucketInfo bucketInfo;

    @BeforeEach
    void setUp() {
        bucketInfo = new ServiceBucketInfo();
    }

    @Test
    void testSetAndGetBucketUser() {
        String user = "testuser";
        bucketInfo.setBucketUser(user);
        assertEquals(user, bucketInfo.getBucketUser());
    }

    @Test
    void testSetAndGetServiceId() {
        long serviceId = 12345L;
        bucketInfo.setServiceId(serviceId);
        assertEquals(serviceId, bucketInfo.getServiceId());
    }

    @Test
    void testSetAndGetRule() {
        String rule = "FIFO";
        bucketInfo.setRule(rule);
        assertEquals(rule, bucketInfo.getRule());
    }

    @Test
    void testSetAndGetPriority() {
        long priority = 1L;
        bucketInfo.setPriority(priority);
        assertEquals(priority, bucketInfo.getPriority());
    }

    @Test
    void testSetAndGetInitialBalance() {
        long balance = 10000L;
        bucketInfo.setInitialBalance(balance);
        assertEquals(balance, bucketInfo.getInitialBalance());
    }

    @Test
    void testSetAndGetCurrentBalance() {
        long balance = 5000L;
        bucketInfo.setCurrentBalance(balance);
        assertEquals(balance, bucketInfo.getCurrentBalance());
    }

    @Test
    void testSetAndGetUsage() {
        long usage = 3000L;
        bucketInfo.setUsage(usage);
        assertEquals(usage, bucketInfo.getUsage());
    }

    @Test
    void testSetAndGetExpiryDate() {
        LocalDateTime expiry = LocalDateTime.now().plusDays(30);
        bucketInfo.setExpiryDate(expiry);
        assertEquals(expiry, bucketInfo.getExpiryDate());
    }

    @Test
    void testSetAndGetServiceStartDate() {
        LocalDateTime startDate = LocalDateTime.now();
        bucketInfo.setServiceStartDate(startDate);
        assertEquals(startDate, bucketInfo.getServiceStartDate());
    }

    @Test
    void testSetAndGetPlanId() {
        String planId = "PLAN001";
        bucketInfo.setPlanId(planId);
        assertEquals(planId, bucketInfo.getPlanId());
    }

    @Test
    void testSetAndGetStatus() {
        String status = "ACTIVE";
        bucketInfo.setStatus(status);
        assertEquals(status, bucketInfo.getStatus());
    }

    @Test
    void testSetAndGetBucketId() {
        long bucketId = 99999L;
        bucketInfo.setBucketId(bucketId);
        assertEquals(bucketId, bucketInfo.getBucketId());
    }

    @Test
    void testSetAndGetConsumptionLimit() {
        long limit = 1000L;
        bucketInfo.setConsumptionLimit(limit);
        assertEquals(limit, bucketInfo.getConsumptionLimit());
    }

    @Test
    void testSetAndGetConsumptionTimeWindow() {
        long window = 3600L;
        bucketInfo.setConsumptionTimeWindow(window);
        assertEquals(window, bucketInfo.getConsumptionTimeWindow());
    }

    @Test
    void testSetAndGetTimeWindow() {
        String timeWindow = "HOURLY";
        bucketInfo.setTimeWindow(timeWindow);
        assertEquals(timeWindow, bucketInfo.getTimeWindow());
    }

    @Test
    void testSetAndGetSessionTimeout() {
        String timeout = "1800";
        bucketInfo.setSessionTimeout(timeout);
        assertEquals(timeout, bucketInfo.getSessionTimeout());
    }

    @Test
    void testSetAndGetBucketExpiryDate() {
        LocalDateTime expiryDate = LocalDateTime.now().plusMonths(6);
        bucketInfo.setBucketExpiryDate(expiryDate);
        assertEquals(expiryDate, bucketInfo.getBucketExpiryDate());
    }

    @Test
    void testToString() {
        bucketInfo.setBucketUser("user1");
        bucketInfo.setServiceId(123L);
        bucketInfo.setCurrentBalance(5000L);

        String result = bucketInfo.toString();
        assertNotNull(result);
        assertTrue(result.contains("user1"));
    }

    @Test
    void testCompleteObject() {
        bucketInfo.setBucketUser("testuser");
        bucketInfo.setServiceId(100L);
        bucketInfo.setRule("PRIORITY");
        bucketInfo.setPriority(1L);
        bucketInfo.setInitialBalance(10000L);
        bucketInfo.setCurrentBalance(7000L);
        bucketInfo.setUsage(3000L);
        bucketInfo.setStatus("ACTIVE");
        bucketInfo.setBucketId(200L);

        assertEquals("testuser", bucketInfo.getBucketUser());
        assertEquals(100L, bucketInfo.getServiceId());
        assertEquals("PRIORITY", bucketInfo.getRule());
        assertEquals(1L, bucketInfo.getPriority());
        assertEquals(10000L, bucketInfo.getInitialBalance());
        assertEquals(7000L, bucketInfo.getCurrentBalance());
        assertEquals(3000L, bucketInfo.getUsage());
        assertEquals("ACTIVE", bucketInfo.getStatus());
        assertEquals(200L, bucketInfo.getBucketId());
    }
}
