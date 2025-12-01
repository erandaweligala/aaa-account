package com.csg.airtel.aaa4j.domain.model.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ConsumptionRecordTest {

    private ConsumptionRecord consumptionRecord;
    private LocalDateTime testDateTime;

    @BeforeEach
    void setUp() {
        testDateTime = LocalDateTime.now();
    }

    @Test
    void testNoArgsConstructor() {
        consumptionRecord = new ConsumptionRecord();
        assertNotNull(consumptionRecord);
    }

    @Test
    void testAllArgsConstructor() {
        Long bytesConsumed = 1024L;
        consumptionRecord = new ConsumptionRecord(testDateTime, bytesConsumed);

        assertEquals(testDateTime, consumptionRecord.getTimestamp());
        assertEquals(bytesConsumed, consumptionRecord.getBytesConsumed());
    }

    @Test
    void testSetAndGetTimestamp() {
        consumptionRecord = new ConsumptionRecord();
        consumptionRecord.setTimestamp(testDateTime);
        assertEquals(testDateTime, consumptionRecord.getTimestamp());
    }

    @Test
    void testSetAndGetBytesConsumed() {
        consumptionRecord = new ConsumptionRecord();
        Long bytes = 2048L;
        consumptionRecord.setBytesConsumed(bytes);
        assertEquals(bytes, consumptionRecord.getBytesConsumed());
    }

    @Test
    void testNullValues() {
        consumptionRecord = new ConsumptionRecord();
        consumptionRecord.setTimestamp(null);
        consumptionRecord.setBytesConsumed(null);

        assertNull(consumptionRecord.getTimestamp());
        assertNull(consumptionRecord.getBytesConsumed());
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
}
