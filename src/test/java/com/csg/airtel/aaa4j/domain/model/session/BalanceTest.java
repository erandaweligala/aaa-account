package com.csg.airtel.aaa4j.domain.model.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BalanceTest {

    private Balance balance;

    @BeforeEach
    void setUp() {
        balance = new Balance();
    }

    @Test
    void testSetAndGetInitialBalance() {
        Long initialBalance = 10000L;
        balance.setInitialBalance(initialBalance);
        assertEquals(initialBalance, balance.getInitialBalance());
    }

    @Test
    void testSetAndGetQuota() {
        Long quota = 5000L;
        balance.setQuota(quota);
        assertEquals(quota, balance.getQuota());
    }

    @Test
    void testSetAndGetServiceExpiry() {
        LocalDateTime expiry = LocalDateTime.now().plusDays(30);
        balance.setServiceExpiry(expiry);
        assertEquals(expiry, balance.getServiceExpiry());
    }

    @Test
    void testSetAndGetBucketExpiryDate() {
        LocalDateTime bucketExpiry = LocalDateTime.now().plusDays(60);
        balance.setBucketExpiryDate(bucketExpiry);
        assertEquals(bucketExpiry, balance.getBucketExpiryDate());
    }

    @Test
    void testSetAndGetBucketId() {
        String bucketId = "BUCKET123";
        balance.setBucketId(bucketId);
        assertEquals(bucketId, balance.getBucketId());
    }

    @Test
    void testSetAndGetServiceId() {
        String serviceId = "SERVICE456";
        balance.setServiceId(serviceId);
        assertEquals(serviceId, balance.getServiceId());
    }

    @Test
    void testSetAndGetPriority() {
        Long priority = 1L;
        balance.setPriority(priority);
        assertEquals(priority, balance.getPriority());
    }

    @Test
    void testSetAndGetServiceStartDate() {
        LocalDateTime startDate = LocalDateTime.now();
        balance.setServiceStartDate(startDate);
        assertEquals(startDate, balance.getServiceStartDate());
    }

    @Test
    void testSetAndGetServiceStatus() {
        String status = "ACTIVE";
        balance.setServiceStatus(status);
        assertEquals(status, balance.getServiceStatus());
    }

    @Test
    void testSetAndGetTimeWindow() {
        String timeWindow = "DAILY";
        balance.setTimeWindow(timeWindow);
        assertEquals(timeWindow, balance.getTimeWindow());
    }

    @Test
    void testSetAndGetConsumptionLimit() {
        Long limit = 1000L;
        balance.setConsumptionLimit(limit);
        assertEquals(limit, balance.getConsumptionLimit());
    }

    @Test
    void testSetAndGetConsumptionLimitWindow() {
        Long window = 3600L;
        balance.setConsumptionLimitWindow(window);
        assertEquals(window, balance.getConsumptionLimitWindow());
    }

    @Test
    void testSetAndGetBucketUsername() {
        String username = "testuser";
        balance.setBucketUsername(username);
        assertEquals(username, balance.getBucketUsername());
    }

    @Test
    void testConsumptionHistoryInitialization() {
        assertNotNull(balance.getConsumptionHistory());
        assertTrue(balance.getConsumptionHistory().isEmpty());
    }

    @Test
    void testSetAndGetConsumptionHistory() {
        List<ConsumptionRecord> records = new ArrayList<>();
        ConsumptionRecord consumptionRecord = new ConsumptionRecord();
        records.add(consumptionRecord);
        balance.setConsumptionHistory(records);
        assertEquals(records, balance.getConsumptionHistory());
        assertEquals(1, balance.getConsumptionHistory().size());
    }

    @Test
    void testSetAndGetIsGroup() {
        balance.setGroup(true);
        assertTrue(balance.isGroup());

        balance.setGroup(false);
        assertFalse(balance.isGroup());
    }
}
