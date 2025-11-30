package com.csg.airtel.aaa4j.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventTypeTest {

    @Test
    void testEnumValues() {
        EventType[] values = EventType.values();
        assertEquals(2, values.length);
    }

    @Test
    void testCreateEvent() {
        EventType eventType = EventType.CREATE_EVENT;
        assertNotNull(eventType);
        assertEquals("CREATE_EVENT", eventType.name());
    }

    @Test
    void testUpdateEvent() {
        EventType eventType = EventType.UPDATE_EVENT;
        assertNotNull(eventType);
        assertEquals("UPDATE_EVENT", eventType.name());
    }

    @Test
    void testValueOf() {
        assertEquals(EventType.CREATE_EVENT, EventType.valueOf("CREATE_EVENT"));
        assertEquals(EventType.UPDATE_EVENT, EventType.valueOf("UPDATE_EVENT"));
    }

    @Test
    void testInvalidValueOf() {
        assertThrows(IllegalArgumentException.class, () -> {
            EventType.valueOf("INVALID_EVENT");
        });
    }

    @Test
    void testEnumEquality() {
        EventType event1 = EventType.CREATE_EVENT;
        EventType event2 = EventType.CREATE_EVENT;
        assertEquals(event1, event2);
        assertSame(event1, event2);
    }

    @Test
    void testEnumInequality() {
        EventType event1 = EventType.CREATE_EVENT;
        EventType event2 = EventType.UPDATE_EVENT;
        assertNotEquals(event1, event2);
    }
}
