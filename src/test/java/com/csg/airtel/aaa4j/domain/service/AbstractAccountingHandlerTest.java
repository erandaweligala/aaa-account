package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.repository.UserBucketRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractAccountingHandlerTest {

    @Mock
    private CacheClient cacheClient;

    @Mock
    private UserBucketRepository userRepository;

    @Mock
    private COAService coaService;

    private AbstractAccountingHandler abstractAccountingHandler;

    @BeforeEach
    void setUp() {
        abstractAccountingHandler = new AbstractAccountingHandler(
            cacheClient, userRepository, coaService
        );
    }

    @Test
    void testFindSessionById_Found() {
        List<Session> sessions = new ArrayList<>();
        Session session = createSession("session-123");
        sessions.add(session);

        Session found = abstractAccountingHandler.findSessionById(sessions, "session-123");

        assertNotNull(found);
        assertEquals("session-123", found.getSessionId());
    }

    @Test
    void testFindSessionById_NotFound() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSession("session-123"));

        Session found = abstractAccountingHandler.findSessionById(sessions, "session-456");

        assertNull(found);
    }

    @Test
    void testFindSessionById_NullSessions() {
        Session found = abstractAccountingHandler.findSessionById(null, "session-123");

        assertNull(found);
    }

    @Test
    void testFindSessionById_EmptySessions() {
        Session found = abstractAccountingHandler.findSessionById(new ArrayList<>(), "session-123");

        assertNull(found);
    }

    @Test
    void testCreateDefaultSession() {
        AccountingRequestDto request = createRequest();

        Session session = abstractAccountingHandler.createDefaultSession(request);

        assertNotNull(session);
        assertEquals(request.sessionId(), session.getSessionId());
        assertEquals(request.username(), session.getUserName());
        assertEquals(request.framedIPAddress(), session.getFramedId());
        assertEquals(request.nasIP(), session.getNasIp());
    }

    @Test
    void testHandleNewSessionUsage_WithGroupId() {
        AccountingRequestDto request = createRequest();
        UserSessionData groupData = UserSessionData.builder()
            .userName("groupuser")
            .build();

        when(cacheClient.getGroupId(anyString()))
            .thenReturn(Uni.createFrom().item("groupId,2,ACTIVE,3600"));
        when(cacheClient.getUserData(anyString()))
            .thenReturn(Uni.createFrom().item(groupData));

        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> Uni.createFrom().voidItem();

        abstractAccountingHandler.handleNewSessionUsage(
            request, "trace-123", processor, abstractAccountingHandler::createDefaultSession
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheClient).getGroupId(anyString());
        verify(cacheClient).getUserData(anyString());
    }

    @Test
    void testGetUserServicesDetails_Success() {
        AccountingRequestDto request = createRequest();
        List<ServiceBucketInfo> buckets = createBuckets();

        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(buckets));

        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> Uni.createFrom().voidItem();

        abstractAccountingHandler.getUserServicesDetails(
            request, "trace-123", processor, abstractAccountingHandler::createDefaultSession
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(userRepository).getServiceBucketsByUserName(anyString());
    }

    @Test
    void testGetUserServicesDetails_NoBuckets() {
        AccountingRequestDto request = createRequest();

        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(new ArrayList<>()));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> Uni.createFrom().voidItem();

        abstractAccountingHandler.getUserServicesDetails(
            request, "trace-123", processor, abstractAccountingHandler::createDefaultSession
        ).subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(coaService).produceAccountingResponseEvent(any(), any(), anyString());
    }

    private AccountingRequestDto createRequest() {
        return new AccountingRequestDto(
            "testuser",
            "session-123",
            AccountingRequestDto.ActionType.START,
            0,
            0L,
            0L,
            0,
            0,
            "192.168.1.1",
            "10.0.0.1",
            "nas-id",
            "port-1",
            0,
            LocalDateTime.now()
        );
    }

    private Session createSession(String sessionId) {
        return new Session(
            sessionId,
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

    private List<ServiceBucketInfo> createBuckets() {
        List<ServiceBucketInfo> buckets = new ArrayList<>();
        ServiceBucketInfo bucket = new ServiceBucketInfo();
        bucket.setServiceId(1L);
        bucket.setBucketId(100L);
        bucket.setConcurrency(2L);
        bucket.setNotificationTemplates(1L);
        bucket.setBucketUser("testuser");
        bucket.setUserStatus("ACTIVE");
        bucket.setSessionTimeout("3600");
        bucket.setCurrentBalance(1000L);
        bucket.setInitialBalance(2000L);
        bucket.setServiceStartDate(LocalDateTime.now());
        bucket.setExpiryDate(LocalDateTime.now().plusDays(30));
        bucket.setStatus("Active");
        bucket.setPriority(1L);
        bucket.setTimeWindow("0-24");
        buckets.add(bucket);
        return buckets;
    }
}
