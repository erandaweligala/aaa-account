package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdleSessionTerminatorSchedulerTest {

    @Mock
    private COAService coaService;

    @Mock
    private SessionExpiryIndex sessionExpiryIndex;

    @Mock
    private IdleSessionConfig config;

    @Mock
    private MonitoringService monitoringService;

    private IdleSessionTerminatorScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(config.enabled()).thenReturn(true);
        when(config.checkIntervalMinutes()).thenReturn(5);
        when(config.batchSize()).thenReturn(100);

        scheduler = new IdleSessionTerminatorScheduler(
            coaService, sessionExpiryIndex, config, monitoringService
        );
    }

    @Test
    void testCheckAndTerminateIdleSessions_Disabled() {
        when(config.enabled()).thenReturn(false);

        scheduler.checkAndTerminateIdleSessions();

        verify(sessionExpiryIndex, never()).getExpiredSessions(anyLong());
    }

    @Test
    void testCheckAndTerminateIdleSessions_NoExpiredSessions() {
        when(sessionExpiryIndex.getExpiredSessions(anyLong()))
            .thenReturn(Uni.createFrom().item(Collections.emptyList()));

        scheduler.checkAndTerminateIdleSessions();

        verify(sessionExpiryIndex).getExpiredSessions(anyLong());
        verify(coaService, never()).clearSessionsBySessionData(anyList());
    }

    @Test
    void testCheckAndTerminateIdleSessions_WithExpiredSessions() {
        List<SessionExpiryIndex.SessionExpiryData> expiredSessions = new ArrayList<>();
        expiredSessions.add(new SessionExpiryIndex.SessionExpiryData("user-1", "session-1"));

        when(sessionExpiryIndex.getExpiredSessions(anyLong()))
            .thenReturn(Uni.createFrom().item(expiredSessions));
        when(coaService.clearSessionsBySessionData(anyList()))
            .thenReturn(Uni.createFrom().item(1));

        scheduler.checkAndTerminateIdleSessions();

        verify(sessionExpiryIndex).getExpiredSessions(anyLong());
        verify(coaService).clearSessionsBySessionData(anyList());
        verify(monitoringService).recordIdleSessionsTerminated(1);
    }

    @Test
    void testCheckAndTerminateIdleSessions_Error() {
        when(sessionExpiryIndex.getExpiredSessions(anyLong()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Test error")));

        scheduler.checkAndTerminateIdleSessions();

        verify(sessionExpiryIndex).getExpiredSessions(anyLong());
        verify(coaService, never()).clearSessionsBySessionData(anyList());
    }

    @Test
    void testCheckAndTerminateIdleSessions_LargeBatch() {
        List<SessionExpiryIndex.SessionExpiryData> expiredSessions = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            expiredSessions.add(new SessionExpiryIndex.SessionExpiryData("user-" + i, "session-" + i));
        }

        when(sessionExpiryIndex.getExpiredSessions(anyLong()))
            .thenReturn(Uni.createFrom().item(expiredSessions));
        when(coaService.clearSessionsBySessionData(anyList()))
            .thenReturn(Uni.createFrom().item(100))
            .thenReturn(Uni.createFrom().item(50));

        scheduler.checkAndTerminateIdleSessions();

        verify(sessionExpiryIndex).getExpiredSessions(anyLong());
        verify(coaService, times(2)).clearSessionsBySessionData(anyList());
        verify(monitoringService).recordIdleSessionsTerminated(100);
        verify(monitoringService).recordIdleSessionsTerminated(50);
    }
}
