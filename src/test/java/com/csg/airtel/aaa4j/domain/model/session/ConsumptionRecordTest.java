package com.csg.airtel.aaa4j.domain.model.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ConsumptionRecordTest {

    private ConsumptionRecord record;
    private LocalDateTime testDateTime;

    @BeforeEach
    void setUp() {
        testDateTime = LocalDateTime.now();
    }

    @Test
    void testNoArgsConstructor() {
        record = new ConsumptionRecord();
        assertNotNull(record);
    }

    @Test
    void testAllArgsConstructor() {
        Long bytesConsumed = 1024L;
        record = new ConsumptionRecord(testDateTime, bytesConsumed);

        assertEquals(testDateTime, record.getTimestamp());
        assertEquals(bytesConsumed, record.getBytesConsumed());
    }

    @Test
    void testSetAndGetTimestamp() {
        record = new ConsumptionRecord();
        record.setTimestamp(testDateTime);
        assertEquals(testDateTime, record.getTimestamp());
    }

    @Test
    void testSetAndGetBytesConsumed() {
        record = new ConsumptionRecord();
        Long bytes = 2048L;
        record.setBytesConsumed(bytes);
        assertEquals(bytes, record.getBytesConsumed());
    }

    @Test
    void testNullValues() {
        record = new ConsumptionRecord();
        record.setTimestamp(null);
        record.setBytesConsumed(null);

        assertNull(record.getTimestamp());
        assertNull(record.getBytesConsumed());
    }

    @Test
    void testLargeByteValues() {
        record = new ConsumptionRecord();
        Long largeValue = Long.MAX_VALUE;
        record.setBytesConsumed(largeValue);
        assertEquals(largeValue, record.getBytesConsumed());
    }

    @Test
    void testZeroByteValues() {
        record = new ConsumptionRecord();
        Long zero = 0L;
        record.setBytesConsumed(zero);
        assertEquals(zero, record.getBytesConsumed());
    }
}
