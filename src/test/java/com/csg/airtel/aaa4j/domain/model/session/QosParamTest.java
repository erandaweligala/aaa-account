package com.csg.airtel.aaa4j.domain.model.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

class QosParamTest {

    private QosParam qosParam;

    @BeforeEach
    void setUp() {
        qosParam = new QosParam();
    }

    @Test
    void testSetAndGetNormalBandwidth() {
        String bandwidth = "100Mbps";
        qosParam.setNormalBandwidth(bandwidth);
        assertEquals(bandwidth, qosParam.getNormalBandwidth());
    }

    @Test
    void testSetAndGetFupBandwidth() {
        String fupBandwidth = "10Mbps";
        qosParam.setFupBandwidth(fupBandwidth);
        assertEquals(fupBandwidth, qosParam.getFupBandwidth());
    }

    @Test
    void testSetBothBandwidths() {
        String normal = "200Mbps";
        String fup = "20Mbps";

        qosParam.setNormalBandwidth(normal);
        qosParam.setFupBandwidth(fup);

        assertEquals(normal, qosParam.getNormalBandwidth());
        assertEquals(fup, qosParam.getFupBandwidth());
    }

    @Test
    void testNullValues() {
        qosParam.setNormalBandwidth(null);
        qosParam.setFupBandwidth(null);

        assertNull(qosParam.getNormalBandwidth());
        assertNull(qosParam.getFupBandwidth());
    }
}
