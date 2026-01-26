package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
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
// todo pls fixed errors
@ExtendWith(MockitoExtension.class)
class AbstractAccountingHandlerAdvancedTest {

    @Mock
    private CacheClient cacheClient;

    @Mock
    private UserBucketRepository userRepository;

    @Mock
    private COAService coaService;

    private AbstractAccountingHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AbstractAccountingHandler(cacheClient, userRepository, coaService);
    }

    @Test
    void testHandleNewSessionUsage_NoGroupId() {
        AccountingRequestDto request = createRequest();
        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> Uni.createFrom().voidItem();

        when(cacheClient.getGroupId(anyString())).thenReturn(Uni.createFrom().nullItem());

        handler.handleNewSessionUsage(request, "trace-123", processor, handler::createDefaultSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheClient).getGroupId(anyString());
    }

    @Test
    void testHandleNewSessionUsage_WithGroupId_NoGroupData() {
        AccountingRequestDto request = createRequest();
        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> Uni.createFrom().voidItem();

        when(cacheClient.getGroupId(anyString()))
            .thenReturn(Uni.createFrom().item("groupId,2,ACTIVE,3600"));
        when(cacheClient.getUserData(anyString())).thenReturn(Uni.createFrom().nullItem());

        handler.handleNewSessionUsage(request, "trace-123", processor, handler::createDefaultSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheClient).getGroupId(anyString());
        verify(cacheClient).getUserData(anyString());
    }

    @Test
    void testHandleNewSessionUsage_WithGroupId_WithGroupData() {
        AccountingRequestDto request = createRequest();
        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> Uni.createFrom().voidItem();

        UserSessionData groupData = UserSessionData.builder()
            .userName("groupuser")
            .build();

        when(cacheClient.getGroupId(anyString()))
            .thenReturn(Uni.createFrom().item("groupId,2,ACTIVE,3600"));
        when(cacheClient.getUserData(anyString())).thenReturn(Uni.createFrom().item(groupData));

        handler.handleNewSessionUsage(request, "trace-123", processor, handler::createDefaultSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheClient).getGroupId(anyString());
        verify(cacheClient).getUserData(anyString());
    }

    @Test
    void testHandleNewSessionUsage_ErrorFetchingGroupId() {
        AccountingRequestDto request = createRequest();
        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> Uni.createFrom().voidItem();

        when(cacheClient.getGroupId(anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));

        handler.handleNewSessionUsage(request, "trace-123", processor, handler::createDefaultSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(cacheClient).getGroupId(anyString());
    }

    @Test
    void testGetUserServicesDetails_MultipleBalances() {
        AccountingRequestDto request = createRequest();
        List<ServiceBucketInfo> buckets = createMultipleBuckets();

        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(buckets));

        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> {
                assertNotNull(userData);
                assertNotNull(userData.getBalance());
                assertEquals(3, userData.getBalance().size());
                return Uni.createFrom().voidItem();
            };

        handler.getUserServicesDetails(request, "trace-123", processor, handler::createDefaultSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(userRepository).getServiceBucketsByUserName(anyString());
    }

    @Test
    void testGetUserServicesDetails_RepositoryError() {
        AccountingRequestDto request = createRequest();

        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("DB error")));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
            .thenReturn(Uni.createFrom().voidItem());

        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> Uni.createFrom().voidItem();

        handler.getUserServicesDetails(request, "trace-123", processor, handler::createDefaultSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(userRepository).getServiceBucketsByUserName(anyString());
    }

    @Test
    void testGetUserServicesDetails_WithGroupBuckets() {
        AccountingRequestDto request = createRequest();
        List<ServiceBucketInfo> buckets = createGroupAndUserBuckets();

        when(userRepository.getServiceBucketsByUserName(anyString()))
            .thenReturn(Uni.createFrom().item(buckets));

        AbstractAccountingHandler.AccountingRequestProcessor processor =
            (userData, req, groupId) -> {
                assertNotNull(userData);
                assertEquals("group-123", userData.getGroupId());
                return Uni.createFrom().voidItem();
            };

        handler.getUserServicesDetails(request, "trace-123", processor, handler::createDefaultSession)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(userRepository).getServiceBucketsByUserName(anyString());
    }

    @Test
    void testCreateDefaultSession_AllFields() {//not assign in curect values
        AccountingRequestDto request = new AccountingRequestDto(
            "testuser",
            "session-123",
            AccountingRequestDto.ActionType.START,
            1000,
            500L,
            400L,
            1,
            2,
            "192.168.1.1",
            "10.0.0.1",
            "nas-id",
            "port-1",
            3,
            LocalDateTime.now()
        );

        Session session = handler.createDefaultSession(request);

        assertNotNull(session);
        assertEquals(request.sessionId(), session.getSessionId());
        assertEquals(request.username(), session.getUserName());
        assertEquals(request.framedIPAddress(), session.getFramedId());
        assertEquals(request.nasIP(), session.getNasIp());
        assertEquals(request.nasPortId(), session.getNasPortId());
        assertNotNull(session.getSessionStartTime());
        assertNotNull(session.getSessionInitiatedTime());
    }

    @Test
    void testFindSessionById_MultipleSessionsWithSameUser() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSessionWithId("session-1", "user1"));
        sessions.add(createSessionWithId("session-2", "user1"));
        sessions.add(createSessionWithId("session-3", "user2"));

        Session found = handler.findSessionById(sessions, "session-2");

        assertNotNull(found);
        assertEquals("session-2", found.getSessionId());
        assertEquals("user1", found.getUserName());
    }

    @Test
    void testFindSessionById_NullSessionId() {
        List<Session> sessions = new ArrayList<>();
        sessions.add(createSessionWithId("session-1", "user1"));

        Session found = handler.findSessionById(sessions, null);

        assertNull(found);
    }

    private AccountingRequestDto createRequest() { //not assign in curect values
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

    private List<ServiceBucketInfo> createMultipleBuckets() {
        List<ServiceBucketInfo> buckets = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ServiceBucketInfo bucket = new ServiceBucketInfo();
            bucket.setServiceId((long) i);
            bucket.setBucketId((long) (100 + i));
            bucket.setBucketUser("testuser");
            bucket.setCurrentBalance(1000L * i);
            bucket.setInitialBalance(2000L * i);
            bucket.setServiceStartDate(LocalDateTime.now());
            bucket.setExpiryDate(LocalDateTime.now().plusDays(30));
            bucket.setStatus("Active");
            bucket.setPriority((long) i);
            bucket.setConcurrency(2L);
            bucket.setNotificationTemplates(1L);
            bucket.setUserStatus("ACTIVE");
            bucket.setSessionTimeout("3600");
            buckets.add(bucket);
        }
        return buckets;
    }

    private List<ServiceBucketInfo> createGroupAndUserBuckets() {
        List<ServiceBucketInfo> buckets = new ArrayList<>();

        ServiceBucketInfo userBucket = new ServiceBucketInfo();
        userBucket.setServiceId(1L);
        userBucket.setBucketId(101L);
        userBucket.setBucketUser("testuser");
        userBucket.setCurrentBalance(1000L);
        userBucket.setInitialBalance(2000L);
        userBucket.setServiceStartDate(LocalDateTime.now());
        userBucket.setExpiryDate(LocalDateTime.now().plusDays(30));
        userBucket.setStatus("Active");
        userBucket.setPriority(2L);
        userBucket.setConcurrency(2L);
        userBucket.setNotificationTemplates(1L);
        userBucket.setUserStatus("ACTIVE");
        userBucket.setSessionTimeout("3600");
        buckets.add(userBucket);

        ServiceBucketInfo groupBucket = new ServiceBucketInfo();
        groupBucket.setServiceId(2L);
        groupBucket.setBucketId(201L);
        groupBucket.setBucketUser("group-123");
        groupBucket.setCurrentBalance(5000L);
        groupBucket.setInitialBalance(10000L);
        groupBucket.setServiceStartDate(LocalDateTime.now());
        groupBucket.setExpiryDate(LocalDateTime.now().plusDays(30));
        groupBucket.setStatus("Active");
        groupBucket.setPriority(1L);
        groupBucket.setConcurrency(5L);
        groupBucket.setNotificationTemplates(1L);
        groupBucket.setUserStatus("ACTIVE");
        groupBucket.setSessionTimeout("7200");
        buckets.add(groupBucket);

        return buckets;
    }

    private Session createSessionWithId(String sessionId, String username) {
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
            username,
            null,
            null,
            null,
            0
        );
    }
}
