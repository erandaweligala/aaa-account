package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionLifecycleManagerTest {

    @Mock
    private SessionExpiryIndex sessionExpiryIndex;

    @Mock
    private IdleSessionConfig config;

    @Mock
    private MonitoringService monitoringService;

    private SessionLifecycleManager sessionLifecycleManager;

    private String testUserId;
    private Session testSession;

    @BeforeEach
    void setUp() {
        when(config.enabled()).thenReturn(true);
        when(config.timeoutMinutes()).thenReturn(30);

        sessionLifecycleManager = new SessionLifecycleManager(
            sessionExpiryIndex, config, monitoringService
        );

        testUserId = "user-123";
        testSession = createSampleSession();
    }

    @Test
    void testOnSessionCreated_Enabled() {
        when(sessionExpiryIndex.registerSession(anyString(), anyString(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());

        sessionLifecycleManager.onSessionCreated(testUserId, testSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(monitoringService).recordSessionCreated();
        verify(sessionExpiryIndex).registerSession(eq(testUserId), eq(testSession.getSessionId()), anyLong());
    }

    @Test
    void testOnSessionCreated_Disabled() {
        when(config.enabled()).thenReturn(false);

        sessionLifecycleManager.onSessionCreated(testUserId, testSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(monitoringService, never()).recordSessionCreated();
        verify(sessionExpiryIndex, never()).registerSession(anyString(), anyString(), anyLong());
    }

    @Test
    void testOnSessionCreated_NullSession() {
        sessionLifecycleManager.onSessionCreated(testUserId, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(sessionExpiryIndex, never()).registerSession(anyString(), anyString(), anyLong());
    }

    @Test
    void testOnSessionActivity_Enabled() {
        when(sessionExpiryIndex.updateSessionExpiry(anyString(), anyString(), anyLong()))
            .thenReturn(Uni.createFrom().voidItem());

        sessionLifecycleManager.onSessionActivity(testUserId, testSession.getSessionId())
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(sessionExpiryIndex).updateSessionExpiry(eq(testUserId), eq(testSession.getSessionId()), anyLong());
    }

    @Test
    void testOnSessionActivity_Disabled() {
        when(config.enabled()).thenReturn(false);

        sessionLifecycleManager.onSessionActivity(testUserId, testSession.getSessionId())
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(sessionExpiryIndex, never()).updateSessionExpiry(anyString(), anyString(), anyLong());
    }

    @Test
    void testOnSessionTerminated_Enabled() {
        when(sessionExpiryIndex.removeSession(anyString(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        sessionLifecycleManager.onSessionTerminated(testUserId, testSession.getSessionId())
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(monitoringService).recordSessionTerminated();
        verify(sessionExpiryIndex).removeSession(eq(testUserId), eq(testSession.getSessionId()));
    }

    @Test
    void testOnSessionTerminated_Disabled() {
        when(config.enabled()).thenReturn(false);

        sessionLifecycleManager.onSessionTerminated(testUserId, testSession.getSessionId())
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(monitoringService, never()).recordSessionTerminated();
        verify(sessionExpiryIndex, never()).removeSession(anyString(), anyString());
    }

    @Test
    void testOnSessionCreated_RegisterFailure() {
        when(sessionExpiryIndex.registerSession(anyString(), anyString(), anyLong()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Register failed")));

        sessionLifecycleManager.onSessionCreated(testUserId, testSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(monitoringService).recordSessionCreated();
        verify(sessionExpiryIndex).registerSession(anyString(), anyString(), anyLong());
    }

    private Session createSampleSession() {
        return new Session(
            "session-123",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            0,
            0L,
            "192.168.1.1",
            "10.0.0.1",
            "port-1",
            false,
            0,
            null,
            "testuser",
            null,
            null,
            null,
            0
        );
    }
}
