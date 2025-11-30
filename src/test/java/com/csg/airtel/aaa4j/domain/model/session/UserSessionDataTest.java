package com.csg.airtel.aaa4j.domain.model.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserSessionDataTest {

    private UserSessionData userSessionData;

    @BeforeEach
    void setUp() {
        userSessionData = new UserSessionData();
    }

    @Test
    void testNoArgsConstructor() {
        assertNotNull(userSessionData);
    }

    @Test
    void testAllArgsConstructor() {
        String sessionTimeout = "3600";
        String userName = "testuser";
        String groupId = "group123";
        List<Balance> balances = new ArrayList<>();
        List<Session> sessions = new ArrayList<>();
        QosParam qosParam = new QosParam();

        userSessionData = new UserSessionData(sessionTimeout, userName, groupId,
                                               balances, sessions, qosParam);

        assertEquals(sessionTimeout, userSessionData.getSessionTimeOut());
        assertEquals(userName, userSessionData.getUserName());
        assertEquals(groupId, userSessionData.getGroupId());
        assertEquals(balances, userSessionData.getBalance());
        assertEquals(sessions, userSessionData.getSessions());
        assertEquals(qosParam, userSessionData.getQosParam());
    }

    @Test
    void testBuilder() {
        String userName = "builderUser";
        String groupId = "builderGroup";

        UserSessionData built = UserSessionData.builder()
                .userName(userName)
                .groupId(groupId)
                .build();

        assertEquals(userName, built.getUserName());
        assertEquals(groupId, built.getGroupId());
    }

    @Test
    void testToBuilder() {
        String originalUserName = "original";
        userSessionData.setUserName(originalUserName);

        UserSessionData modified = userSessionData.toBuilder()
                .groupId("newGroup")
                .build();

        assertEquals(originalUserName, modified.getUserName());
        assertEquals("newGroup", modified.getGroupId());
    }

    @Test
    void testSetAndGetSessionTimeOut() {
        String timeout = "7200";
        userSessionData.setSessionTimeOut(timeout);
        assertEquals(timeout, userSessionData.getSessionTimeOut());
    }

    @Test
    void testSetAndGetUserName() {
        String userName = "john.doe";
        userSessionData.setUserName(userName);
        assertEquals(userName, userSessionData.getUserName());
    }

    @Test
    void testSetAndGetGroupId() {
        String groupId = "GRP001";
        userSessionData.setGroupId(groupId);
        assertEquals(groupId, userSessionData.getGroupId());
    }

    @Test
    void testSetAndGetBalance() {
        List<Balance> balances = new ArrayList<>();
        Balance balance1 = new Balance();
        balance1.setQuota(1000L);
        balances.add(balance1);

        userSessionData.setBalance(balances);
        assertEquals(balances, userSessionData.getBalance());
        assertEquals(1, userSessionData.getBalance().size());
    }

    @Test
    void testSetAndGetSessions() {
        List<Session> sessions = new ArrayList<>();
        Session session1 = new Session();
        session1.setSessionId("SES001");
        sessions.add(session1);

        userSessionData.setSessions(sessions);
        assertEquals(sessions, userSessionData.getSessions());
        assertEquals(1, userSessionData.getSessions().size());
    }

    @Test
    void testSetAndGetQosParam() {
        QosParam qosParam = new QosParam();
        userSessionData.setQosParam(qosParam);
        assertEquals(qosParam, userSessionData.getQosParam());
    }

    @Test
    void testNullValues() {
        userSessionData.setUserName(null);
        userSessionData.setGroupId(null);
        userSessionData.setBalance(null);

        assertNull(userSessionData.getUserName());
        assertNull(userSessionData.getGroupId());
        assertNull(userSessionData.getBalance());
    }
}
