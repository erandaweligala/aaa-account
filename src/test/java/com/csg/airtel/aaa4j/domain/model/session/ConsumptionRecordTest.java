package com.csg.airtel.aaa4j.domain.model.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ConsumptionRecordTest {

    private ConsumptionRecord consumptionRecord;
    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        testDate = LocalDate.now();
    }

    @Test
    void testNoArgsConstructor() {
        consumptionRecord = new ConsumptionRecord();
        assertNotNull(consumptionRecord);
    }

    @Test
    void testTwoArgsConstructor() {
        Long bytesConsumed = 1024L;
        consumptionRecord = new ConsumptionRecord(testDate, bytesConsumed);

        assertEquals(testDate, consumptionRecord.getDate());
        assertEquals(bytesConsumed, consumptionRecord.getBytesConsumed());
        assertEquals(1, consumptionRecord.getRequestCount());
    }

    @Test
    void testAllArgsConstructor() {
        Long bytesConsumed = 1024L;
        Integer requestCount = 5;
        consumptionRecord = new ConsumptionRecord(testDate, bytesConsumed, requestCount);

        assertEquals(testDate, consumptionRecord.getDate());
        assertEquals(bytesConsumed, consumptionRecord.getBytesConsumed());
        assertEquals(requestCount, consumptionRecord.getRequestCount());
    }

    @Test
    void testSetAndGetDate() {
        consumptionRecord = new ConsumptionRecord();
        consumptionRecord.setDate(testDate);
        assertEquals(testDate, consumptionRecord.getDate());
    }

    @Test
    void testSetAndGetBytesConsumed() {
        consumptionRecord = new ConsumptionRecord();
        Long bytes = 2048L;
        consumptionRecord.setBytesConsumed(bytes);
        assertEquals(bytes, consumptionRecord.getBytesConsumed());
    }

    @Test
    void testSetAndGetRequestCount() {
        consumptionRecord = new ConsumptionRecord();
        Integer count = 10;
        consumptionRecord.setRequestCount(count);
        assertEquals(count, consumptionRecord.getRequestCount());
    }

    @Test
    void testAddConsumption() {
        consumptionRecord = new ConsumptionRecord(testDate, 1000L, 1);

        consumptionRecord.addConsumption(500L);

        assertEquals(1500L, consumptionRecord.getBytesConsumed());
        assertEquals(2, consumptionRecord.getRequestCount());
    }

    @Test
    void testAddConsumptionMultipleTimes() {
        consumptionRecord = new ConsumptionRecord(testDate, 1000L, 1);

        consumptionRecord.addConsumption(200L);
        consumptionRecord.addConsumption(300L);
        consumptionRecord.addConsumption(500L);

        assertEquals(2000L, consumptionRecord.getBytesConsumed());
        assertEquals(4, consumptionRecord.getRequestCount());
    }

    @Test
    void testNullValues() {
        consumptionRecord = new ConsumptionRecord();
        consumptionRecord.setDate(null);
        consumptionRecord.setBytesConsumed(null);
        consumptionRecord.setRequestCount(null);

        assertNull(consumptionRecord.getDate());
        assertNull(consumptionRecord.getBytesConsumed());
        assertNull(consumptionRecord.getRequestCount());
    }

    @Test
    void testLargeByteValues() {
        consumptionRecord = new ConsumptionRecord();
        Long largeValue = Long.MAX_VALUE;
        consumptionRecord.setBytesConsumed(largeValue);
        assertEquals(largeValue, consumptionRecord.getBytesConsumed());
    }

    @Test
    void testZeroByteValues() {
        consumptionRecord = new ConsumptionRecord();
        Long zero = 0L;
        consumptionRecord.setBytesConsumed(zero);
        assertEquals(zero, consumptionRecord.getBytesConsumed());
    }

    @Test
    void testDailyAggregationScenario() {
        // Simulate 4 requests per hour for a day (96 requests total)
        LocalDate today = LocalDate.now();
        consumptionRecord = new ConsumptionRecord(today, 0L, 0);

        // Simulate 96 requests with 100MB each
        for (int i = 0; i < 96; i++) {
            consumptionRecord.addConsumption(100_000_000L); // 100MB
        }

        assertEquals(9_600_000_000L, consumptionRecord.getBytesConsumed()); // 9.6GB total
        assertEquals(96, consumptionRecord.getRequestCount());
    }
}
