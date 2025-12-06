package com.csg.airtel.aaa4j.domain.model.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    private Session session;
    private LocalDateTime testDateTime;

    @BeforeEach
    void setUp() {
        testDateTime = LocalDateTime.now();
    }

    @Test
    void testNoArgsConstructor() {
        session = new Session();
        assertNotNull(session);
    }

    @Test
    void testAllArgsConstructor() {
        String sessionId = "SESSION123";
        LocalDateTime initiatedTime = testDateTime;
        String previousBucketId = "BUCKET456";
        Integer sessionTime = 3600;
        Long previousUsage = 1024L;
        String framedId = "10.0.0.1";
        String nasIp = "192.168.1.1";

        session = new Session(sessionId, initiatedTime, previousBucketId,
                             sessionTime, previousUsage, framedId, nasIp,false);

        assertEquals(sessionId, session.getSessionId());
        assertEquals(initiatedTime, session.getSessionInitiatedTime());
        assertEquals(previousBucketId, session.getPreviousUsageBucketId());
        assertEquals(sessionTime, session.getSessionTime());
        assertEquals(previousUsage, session.getPreviousTotalUsageQuotaValue());
        assertEquals(framedId, session.getFramedId());
        assertEquals(nasIp, session.getNasIp());
    }

    @Test
    void testSetAndGetSessionId() {
        session = new Session();
        String sessionId = "SESSION789";
        session.setSessionId(sessionId);
        assertEquals(sessionId, session.getSessionId());
    }

    @Test
    void testSetAndGetSessionInitiatedTime() {
        session = new Session();
        session.setSessionInitiatedTime(testDateTime);
        assertEquals(testDateTime, session.getSessionInitiatedTime());
    }

    @Test
    void testSetAndGetPreviousUsageBucketId() {
        session = new Session();
        String bucketId = "PREV_BUCKET";
        session.setPreviousUsageBucketId(bucketId);
        assertEquals(bucketId, session.getPreviousUsageBucketId());
    }

    @Test
    void testSetAndGetSessionTime() {
        session = new Session();
        Integer time = 7200;
        session.setSessionTime(time);
        assertEquals(time, session.getSessionTime());
    }

    @Test
    void testSetAndGetPreviousTotalUsageQuotaValue() {
        session = new Session();
        Long usage = 2048L;
        session.setPreviousTotalUsageQuotaValue(usage);
        assertEquals(usage, session.getPreviousTotalUsageQuotaValue());
    }

    @Test
    void testSetAndGetFramedId() {
        session = new Session();
        String framedId = "172.16.0.1";
        session.setFramedId(framedId);
        assertEquals(framedId, session.getFramedId());
    }

    @Test
    void testSetAndGetNasIp() {
        session = new Session();
        String nasIp = "192.168.100.1";
        session.setNasIp(nasIp);
        assertEquals(nasIp, session.getNasIp());
    }

    @Test
    void testSetNullValues() {
        session = new Session();
        session.setSessionId(null);
        session.setPreviousUsageBucketId(null);
        session.setSessionTime(null);
        session.setPreviousTotalUsageQuotaValue(null);

        assertNull(session.getSessionId());
        assertNull(session.getPreviousUsageBucketId());
        assertNull(session.getSessionTime());
        assertNull(session.getPreviousTotalUsageQuotaValue());
    }
}
