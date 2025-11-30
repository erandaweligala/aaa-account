package com.csg.airtel.aaa4j.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProcessTypeTest {

    @Test
    void testEnumValues() {
        ProcessType[] values = ProcessType.values();
        assertEquals(3, values.length);
    }

    @Test
    void testCoaType() {
        ProcessType processType = ProcessType.COA;
        assertNotNull(processType);
        assertEquals("COA", processType.name());
    }

    @Test
    void testDisconnectType() {
        ProcessType processType = ProcessType.DISCONNECT;
        assertNotNull(processType);
        assertEquals("DISCONNECT", processType.name());
    }

    @Test
    void testStopOnlyType() {
        ProcessType processType = ProcessType.STOP_ONLY;
        assertNotNull(processType);
        assertEquals("STOP_ONLY", processType.name());
    }

    @Test
    void testValueOf() {
        assertEquals(ProcessType.COA, ProcessType.valueOf("COA"));
        assertEquals(ProcessType.DISCONNECT, ProcessType.valueOf("DISCONNECT"));
        assertEquals(ProcessType.STOP_ONLY, ProcessType.valueOf("STOP_ONLY"));
    }

    @Test
    void testInvalidValueOf() {
        assertThrows(IllegalArgumentException.class, () -> {
            ProcessType.valueOf("INVALID_TYPE");
        });
    }

    @Test
    void testEnumEquality() {
        ProcessType type1 = ProcessType.COA;
        ProcessType type2 = ProcessType.COA;
        assertEquals(type1, type2);
        assertSame(type1, type2);
    }

    @Test
    void testEnumInequality() {
        ProcessType type1 = ProcessType.COA;
        ProcessType type2 = ProcessType.DISCONNECT;
        assertNotEquals(type1, type2);
    }

    @Test
    void testAllEnumValues() {
        ProcessType[] values = ProcessType.values();
        assertTrue(contains(values, ProcessType.COA));
        assertTrue(contains(values, ProcessType.DISCONNECT));
        assertTrue(contains(values, ProcessType.STOP_ONLY));
    }

    private boolean contains(ProcessType[] array, ProcessType value) {
        for (ProcessType type : array) {
            if (type == value) {
                return true;
            }
        }
        return false;
    }
}
