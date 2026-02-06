package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex.SessionExpiryEntry;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdleSessionTerminatorSchedulerTest {

    @Mock CacheClient cacheClient;
    @Mock SessionExpiryIndex sessionExpiryIndex;
    @Mock IdleSessionConfig config;
    @Mock AccountProducer accountProducer;
    @Mock MonitoringService monitoringService;

    @InjectMocks
    IdleSessionTerminatorScheduler scheduler;

    private static final String USER_ID = "user123";
    private static final String SESSION_ID = "sess_001";

    @BeforeEach
    void setUp() {
        lenient().when(config.enabled()).thenReturn(true);
        lenient().when(config.timeoutMinutes()).thenReturn(30);
        lenient().when(config.batchSize()).thenReturn(100);

        // Mock Index Stats (invoked at start)
        lenient().when(sessionExpiryIndex.getTotalIndexedSessions()).thenReturn(Uni.createFrom().item(10L));
        lenient().when(sessionExpiryIndex.getExpiredSessionCount(anyLong())).thenReturn(Uni.createFrom().item(5L));
    }

    @Test
    void testTerminateIdleSessions_Disabled() {
        when(config.enabled()).thenReturn(false);
        scheduler.terminateIdleSessions();
        verify(sessionExpiryIndex, never()).getExpiredSessions(anyLong(), anyInt());
    }

    @Test
    void testTerminateIdleSessions_FullFlow_Success() {
        // 1. Setup Expired Entry
        SessionExpiryIndex.SessionExpiryEntry entry = mock(SessionExpiryIndex.SessionExpiryEntry.class);
        when(entry.userId()).thenReturn(USER_ID);
        when(entry.sessionId()).thenReturn(SESSION_ID);
        when(entry.rawMember()).thenReturn("raw_member_string");

        when(sessionExpiryIndex.getExpiredSessions(anyLong(), anyInt()))
                .thenReturn(Multi.createFrom().items(entry))
                .thenReturn(Multi.createFrom().empty());

        // 2. Setup User Data
        UserSessionData userData = new UserSessionData();
        userData.setUserName(USER_ID);

        Session session = new Session();
        session.setSessionId(SESSION_ID);
        session.setSessionStartTime(LocalDateTime.now().minusHours(1));
        session.setSessionInitiatedTime(LocalDateTime.now().minusHours(1));
        session.setAbsoluteTimeOut("60");
        session.setPreviousUsageBucketId("bucket1");
        session.setAvailableBalance(100);

        Balance balance = new Balance();
        balance.setBucketId("bucket1");
        balance.setBucketUsername(USER_ID);
        balance.setQuota(150L); // Keep this > AvailableBalance to trigger DB write
        balance.setServiceId("service1");
        // FIX: Populate mandatory fields for MappingUtil to prevent NPE
        balance.setInitialBalance(500L); // This was missing and caused the NPE


        userData.setSessions(new java.util.ArrayList<>(List.of(session)));
        userData.setBalance(List.of(balance));

        // 3. Setup Reactive Mocks with explicit typing
        when(cacheClient.getUserDataBatchAsMap(anyList()))
                .thenReturn(Uni.createFrom().<Map<String, UserSessionData>>item(Map.of(USER_ID, userData)));

        when(accountProducer.produceDBWriteEvent(any(DBWriteRequest.class)))
                .thenReturn(Uni.createFrom().voidItem());

        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(UserSessionData.class), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        when(sessionExpiryIndex.removeSessions(anyList()))
                .thenReturn(Uni.createFrom().<Integer>item(1));

        // Setup Index Stats Mocks
        when(sessionExpiryIndex.getTotalIndexedSessions()).thenReturn(Uni.createFrom().item(10L));
        when(sessionExpiryIndex.getExpiredSessionCount(anyLong())).thenReturn(Uni.createFrom().item(5L));

        // Execute
        scheduler.terminateIdleSessions();

        // Verify
        // These should now be reached because the NPE is resolved
        verify(monitoringService).recordIdleSessionsTerminated(1);
        verify(accountProducer, atLeastOnce()).produceDBWriteEvent(any(DBWriteRequest.class));
        verify(cacheClient).updateUserAndRelatedCaches(eq(USER_ID), any(UserSessionData.class), eq(USER_ID));
    }
    @Test
    void testTerminateIdleSessions_AbsoluteTimeoutExceeded() {
        // 1. Setup Index Entry
        SessionExpiryIndex.SessionExpiryEntry entry = mock(SessionExpiryIndex.SessionExpiryEntry.class);
        when(entry.userId()).thenReturn(USER_ID);
        when(entry.sessionId()).thenReturn("other_session");
        when(entry.rawMember()).thenReturn("raw_member_string");

        // Use items() for Multi and ensure it stops after second call
        when(sessionExpiryIndex.getExpiredSessions(anyLong(), anyInt()))
                .thenReturn(Multi.createFrom().items(entry))
                .thenReturn(Multi.createFrom().empty());

        // 2. Setup User Data
        UserSessionData userData = new UserSessionData();
        userData.setUserName(USER_ID);

        Session absExpiredSession = new Session();
        absExpiredSession.setSessionId("abs_expired");
        LocalDateTime startTime = LocalDateTime.now().minusMinutes(10);
        absExpiredSession.setSessionStartTime(startTime);
        absExpiredSession.setSessionInitiatedTime(startTime);
        absExpiredSession.setAbsoluteTimeOut("5"); // 5 < 10

        userData.setSessions(new java.util.ArrayList<>(List.of(absExpiredSession)));

        // 3. Mock with explicit typing to resolve 'thenReturn'
        when(cacheClient.getUserDataBatchAsMap(anyList()))
                .thenReturn(Uni.createFrom().<Map<String, UserSessionData>>item(Map.of(USER_ID, userData)));

        when(sessionExpiryIndex.removeSessions(anyList()))
                .thenReturn(Uni.createFrom().<Integer>item(1));

        // Match the exact argument type for UserSessionData
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(UserSessionData.class), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        // Stats Mocks (to cover logIndexStats lines)
        when(sessionExpiryIndex.getTotalIndexedSessions()).thenReturn(Uni.createFrom().item(1L));
        when(sessionExpiryIndex.getExpiredSessionCount(anyLong())).thenReturn(Uni.createFrom().item(1L));

        // Execute
        scheduler.terminateIdleSessions();

        // Verify
        verify(monitoringService).recordIdleSessionsTerminated(1);
        verify(cacheClient).updateUserAndRelatedCaches(eq(USER_ID), any(), eq(USER_ID));
    }
    @Test
    void testProcessUsers_UserDataNotFound() {
        SessionExpiryEntry entry = mock(SessionExpiryEntry.class);
        when(entry.userId()).thenReturn("unknown_user");

        when(sessionExpiryIndex.getExpiredSessions(anyLong(), anyInt()))
                .thenReturn(Multi.createFrom().item(entry))
                .thenReturn(Multi.createFrom().empty());

        // Return empty map for user data
        when(cacheClient.getUserDataBatchAsMap(anyList())).thenReturn(Uni.createFrom().item(Collections.emptyMap()));
        when(sessionExpiryIndex.removeSessions(anyList())).thenReturn(Uni.createFrom().item(1));

        scheduler.terminateIdleSessions();

        verify(cacheClient, never()).updateUserAndRelatedCaches(anyString(), any(), anyString());
    }

    @Test
    void testInvalidAbsoluteTimeoutFormat() {
        SessionExpiryEntry entry = mock(SessionExpiryEntry.class);
        when(entry.userId()).thenReturn(USER_ID);
        when(sessionExpiryIndex.getExpiredSessions(anyLong(), anyInt())).thenReturn(Multi.createFrom().item(entry)).thenReturn(Multi.createFrom().empty());

        UserSessionData userData = new UserSessionData();
        Session session = new Session();
        session.setSessionId("sess_1");
        session.setAbsoluteTimeOut("invalid_number"); // This will trigger the catch block
        session.setSessionStartTime(LocalDateTime.now());
        session.setSessionInitiatedTime(LocalDateTime.now());
        userData.setSessions(List.of(session));

        when(cacheClient.getUserDataBatchAsMap(anyList())).thenReturn(Uni.createFrom().item(Map.of(USER_ID, userData)));
        when(sessionExpiryIndex.removeSessions(anyList())).thenReturn(Uni.createFrom().item(1));

        // Should not crash, just log error and return false for timeout check
        scheduler.terminateIdleSessions();
        verify(monitoringService).recordIdleSessionsTerminated(0);
    }
}