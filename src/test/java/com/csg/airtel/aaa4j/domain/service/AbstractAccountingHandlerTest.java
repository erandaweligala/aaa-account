package com.csg.airtel.aaa4j.domain.service;


import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.service.AbstractAccountingHandler;
import com.csg.airtel.aaa4j.domain.service.COAService;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.repository.UserBucketRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractAccountingHandlerTest {

    @Mock
    CacheClient cacheUtil;
    @Mock
    UserBucketRepository userRepository;
    @Mock
    COAService coaService;

    @InjectMocks
    AbstractAccountingHandler handler;

    private AccountingRequestDto request;
    private final String TRACE_ID = "trace-123";

    @BeforeEach
    void setup() {
        // Mock a basic request record
        request = mock(AccountingRequestDto.class);
        request = mock(AccountingRequestDto.class);
        // Use lenient() for fields that are only used in SOME code paths
        lenient().when(request.username()).thenReturn("testUser");
        lenient().when(request.sessionId()).thenReturn("session-abc");
        lenient().when(request.nasIP()).thenReturn("127.0.0.1");
        lenient().when(request.framedIPAddress()).thenReturn("192.168.1.1");
    }

    /**
     * Scenario: Cache has no group ID. Fallback to repository (getUserServicesDetails).
     */
    @Test
    void testHandleNewSessionUsage_CacheGroupIdNull() {
        // Arrange
        when(cacheUtil.getGroupId(anyString())).thenReturn(Uni.createFrom().item((String) null));

        // Mock repository returning empty list to trigger the coaService flow
        when(userRepository.getServiceBucketsByUserName("testUser"))
                .thenReturn(Uni.createFrom().item(Collections.emptyList()));
        when(coaService.produceAccountingResponseEvent(any(), any(), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        AbstractAccountingHandler.AccountingRequestProcessor processor = (data, req, id) -> Uni.createFrom().voidItem();
        Function<AccountingRequestDto, Session> creator = req -> handler.createDefaultSession(req);

        // Act
        Uni<Void> result = handler.handleNewSessionUsage(request, TRACE_ID, processor, creator);

        // Assert
        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();
        verify(userRepository).getServiceBucketsByUserName("testUser");
        verify(coaService).produceAccountingResponseEvent(any(), any(), eq("testUser"));
    }

    /**
     * Scenario: Cache has Group ID and User Data is found.
     */
    @Test
    void testHandleNewSessionUsage_CacheFound() {
        // Arrange
        String cacheGroupId = "group123,someExtra";
        UserSessionData mockData = UserSessionData.builder().userName("testUser").build();

        when(cacheUtil.getGroupId("testUser")).thenReturn(Uni.createFrom().item(cacheGroupId));
        when(cacheUtil.getUserData("group123")).thenReturn(Uni.createFrom().item(mockData));

        // Mock processor to verify it's called
        AbstractAccountingHandler.AccountingRequestProcessor processor = mock(AbstractAccountingHandler.AccountingRequestProcessor.class);
        when(processor.process(any(), any(), anyString())).thenReturn(Uni.createFrom().voidItem());

        Function<AccountingRequestDto, Session> creator = req -> null;

        // Act
        handler.handleNewSessionUsage(request, TRACE_ID, processor, creator)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        // Assert
        verify(processor).process(eq(mockData), eq(request), eq(cacheGroupId));
    }

    /**
     * Scenario: Service buckets exist. Verify proper mapping to UserSessionData.
     */
    @Test
    void testGetUserServicesDetails_WithBuckets() {
        // Arrange
        ServiceBucketInfo bucket = mock(ServiceBucketInfo.class);
        when(bucket.getBucketUser()).thenReturn("otherUser"); // triggers group ID logic
        when(bucket.getConcurrency()).thenReturn(5L);
        when(bucket.getUserStatus()).thenReturn("ACTIVE");

        when(userRepository.getServiceBucketsByUserName("testUser"))
                .thenReturn(Uni.createFrom().item(List.of(bucket)));

        AbstractAccountingHandler.AccountingRequestProcessor processor = (data, req, id) -> {
            assertEquals("otherUser", data.getGroupId());
            assertEquals(5L, data.getConcurrency());
            return Uni.createFrom().voidItem();
        };

        // Act & Assert
        handler.getUserServicesDetails(request, TRACE_ID, processor, req -> handler.createDefaultSession(req))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();
    }

    @Test
    void testFindSessionById() {
        Session s1 = new Session(); s1.setSessionId("123");
        Session s2 = new Session(); s2.setSessionId("456");

        assertNotNull(handler.findSessionById(List.of(s1, s2), "123"));
        assertNull(handler.findSessionById(List.of(s1), "999"));
        assertNull(handler.findSessionById(null, "123"));
    }
}