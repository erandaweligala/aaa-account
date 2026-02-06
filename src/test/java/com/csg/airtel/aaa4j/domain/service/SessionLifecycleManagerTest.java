package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.IdleSessionConfig;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.external.clients.SessionExpiryIndex;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    private static final String USER_ID = "testUser";
    private static final String SESSION_ID = "testSession123";

    @BeforeEach
    void setUp() {
        sessionLifecycleManager = new SessionLifecycleManager(sessionExpiryIndex, config, monitoringService);
    }

    // --- onSessionCreated Tests ---

    @Test
    @DisplayName("onSessionCreated: Should register session when enabled and valid")
    void onSessionCreated_Success() {
        // Arrange
        when(config.enabled()).thenReturn(true);
        when(config.timeoutMinutes()).thenReturn(30);
        Session session = createMockSession(SESSION_ID, LocalDateTime.now());
        when(sessionExpiryIndex.registerSession(anyString(), anyString(), anyLong()))
                .thenReturn(Uni.createFrom().voidItem());

        // Act
        sessionLifecycleManager.onSessionCreated(USER_ID, session)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        // Assert
        verify(monitoringService).recordSessionCreated();
        verify(sessionExpiryIndex).registerSession(eq(USER_ID), eq(SESSION_ID), anyLong());
    }

    @Test
    @DisplayName("onSessionCreated: Should do nothing if config is disabled")
    void onSessionCreated_Disabled() {
        when(config.enabled()).thenReturn(false);

        sessionLifecycleManager.onSessionCreated(USER_ID, new Session())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verifyNoInteractions(sessionExpiryIndex, monitoringService);
    }

    @Test
    @DisplayName("onSessionCreated: Should handle null session or sessionId")
    void onSessionCreated_NullInputs() {
        when(config.enabled()).thenReturn(true);

        sessionLifecycleManager.onSessionCreated(USER_ID, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        Session sessionNoId = new Session(); // sessionId is null
        sessionLifecycleManager.onSessionCreated(USER_ID, sessionNoId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verifyNoInteractions(sessionExpiryIndex);
    }

    @Test
    @DisplayName("onSessionCreated: Should recover gracefully on repository failure")
    void onSessionCreated_Failure() {
        when(config.enabled()).thenReturn(true);
        when(config.timeoutMinutes()).thenReturn(15);
        Session session = createMockSession(SESSION_ID, null); // Test null timestamp branch

        when(sessionExpiryIndex.registerSession(any(), any(), anyLong()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("DB Down")));

        sessionLifecycleManager.onSessionCreated(USER_ID, session)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted(); // Should recover with null/void via recoverWithNull

        verify(sessionExpiryIndex).registerSession(any(), any(), anyLong());
    }

    // --- onSessionActivity Tests ---

    @Test
    @DisplayName("onSessionActivity: Should update expiry time")
    void onSessionActivity_Success() {
        when(config.enabled()).thenReturn(true);
        when(config.timeoutMinutes()).thenReturn(60);
        when(sessionExpiryIndex.updateSessionExpiry(anyString(), anyString(), anyLong()))
                .thenReturn(Uni.createFrom().voidItem());

        sessionLifecycleManager.onSessionActivity(USER_ID, SESSION_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(sessionExpiryIndex).updateSessionExpiry(eq(USER_ID), eq(SESSION_ID), anyLong());
    }

    @Test
    @DisplayName("onSessionActivity: Should ignore if parameters are null")
    void onSessionActivity_NullParams() {
        when(config.enabled()).thenReturn(true);

        sessionLifecycleManager.onSessionActivity(null, SESSION_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verifyNoInteractions(sessionExpiryIndex);
    }

    // --- onSessionTerminated Tests ---

    @Test
    @DisplayName("onSessionTerminated: Should remove session and record metric")
    void onSessionTerminated_Success() {
        when(config.enabled()).thenReturn(true);
        when(sessionExpiryIndex.removeSession(USER_ID, SESSION_ID))
                .thenReturn(Uni.createFrom().voidItem());

        sessionLifecycleManager.onSessionTerminated(USER_ID, SESSION_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(monitoringService).recordSessionTerminated();
        verify(sessionExpiryIndex).removeSession(USER_ID, SESSION_ID);
    }

    @Test
    @DisplayName("onSessionTerminated: Should recover from errors")
    void onSessionTerminated_ErrorRecovery() {
        when(config.enabled()).thenReturn(true);
        when(sessionExpiryIndex.removeSession(any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Network Error")));

        sessionLifecycleManager.onSessionTerminated(USER_ID, SESSION_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(sessionExpiryIndex).removeSession(USER_ID, SESSION_ID);
    }

    // --- Helper Methods ---

    private Session createMockSession(String id, LocalDateTime initiatedTime) {
        Session session = new Session();
        session.setSessionId(id);
        session.setSessionInitiatedTime(initiatedTime);
        return session;
    }
}