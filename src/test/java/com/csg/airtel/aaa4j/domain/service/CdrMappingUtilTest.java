package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.model.cdr.EventTypes;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CdrMappingUtilTest {

    @Test
    @DisplayName("Test private constructor for coverage")
    void testPrivateConstructor() throws Exception {
        Constructor<CdrMappingUtil> constructor = CdrMappingUtil.class.getDeclaredConstructor();
        // Check if it is private
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                "Constructor should be private");

        // Set accessible and invoke to cover the line
        constructor.setAccessible(true);
        CdrMappingUtil instance = constructor.newInstance();

        // Verify instance is created (satisfies coverage)
        assertNotNull(instance);
    }

    @Test
    @DisplayName("Should build Start CDR Event correctly")
    void testBuildStartCDREvent() {
        AccountingRequestDto request = mockRequest();
        Session session = mockSession();

        AccountingCDREvent event = CdrMappingUtil.buildStartCDREvent(request, session);

        assertNotNull(event);
        assertEquals("ACCOUNTING_START", event.getEventType());
        assertEquals("Start", event.getPayload().getAccounting().getAcctStatusType());
        assertEquals(0L, event.getPayload().getAccounting().getTotalUsage());
    }

    @Test
    @DisplayName("Should build Interim CDR Event with usage calculations")
    void testBuildInterimCDREvent() {
        // Test Gigaword logic: (1 * 2^32) + 100 = 4294967396
        AccountingRequestDto request = mock(AccountingRequestDto.class);
        when(request.sessionId()).thenReturn("sess-123");
        when(request.inputOctets()).thenReturn(50);
        when(request.outputOctets()).thenReturn(50);
        when(request.inputGigaWords()).thenReturn(1);
        when(request.outputGigaWords()).thenReturn(0);
        when(request.sessionTime()).thenReturn(3600);

        Session session = mockSession();
        when(session.getPreviousTotalUsageQuotaValue()).thenReturn(100L);

        AccountingCDREvent event = CdrMappingUtil.buildInterimCDREvent(request, session);

        assertEquals(4294967396L, event.getPayload().getAccounting().getTotalUsage());
        assertEquals(4294967296L, event.getPayload().getAccounting().getSessionUsage()); // total - previous (100)
    }


    @Test
    @DisplayName("Should build COA Disconnect Event with all Radius attributes")
    void testBuildCoaDisconnectCDREvent() {
        Session session = mockSession();
        String username = "testUser";

        AccountingCDREvent event = CdrMappingUtil.buildCoaDisconnectCDREvent(session, username);

        assertNotNull(event);
        assertEquals("COA_DISCONNECT_REQUEST", event.getEventType());
        assertEquals(2, event.getPayload().getRadius().getAttributes().size());
        assertEquals("Disconnect-Request", event.getPayload().getCoa().getCoaType());
    }

    @Test
    @DisplayName("Should successfully generate and send CDR asynchronously")
    void testGenerateAndSendCDR_Success() {
        AccountingRequestDto request = mockRequest();
        Session session = mockSession();
        AccountProducer producer = mock(AccountProducer.class);

        // FIX: Use nullItem() instead of item(null)
        when(producer.produceAccountingCDREvent(any()))
                .thenReturn(Uni.createFrom().nullItem());

        BiFunction<AccountingRequestDto, Session, AccountingCDREvent> builder =
                (req, sess) -> CdrMappingUtil.buildStartCDREvent(req, sess);

        CdrMappingUtil.generateAndSendCDR(request, session, producer, builder, "service-1", "bucket-1");

        verify(producer, times(1)).produceAccountingCDREvent(any());
    }

    @Test
    @DisplayName("Should handle producer failure in generateAndSendCDR")
    void testGenerateAndSendCDR_ProducerFailure() {
        AccountingRequestDto request = mockRequest();
        Session session = mockSession();
        AccountProducer producer = mock(AccountProducer.class);

        when(producer.produceAccountingCDREvent(any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Kafka Down")));

        CdrMappingUtil.generateAndSendCDR(request, session, producer, CdrMappingUtil::buildStartCDREvent, "s1", "b1");

        verify(producer).produceAccountingCDREvent(any());
        // Since it's async and handled via .with(), we just ensure it doesn't throw exception to the caller
    }

    @Test
    @DisplayName("Should catch internal errors during CDR building")
    void testGenerateAndSendCDR_InternalError() {
        AccountingRequestDto request = mockRequest();
        AccountProducer producer = mock(AccountProducer.class);

        // Passing null session to trigger a NullPointerException inside the builder
        CdrMappingUtil.generateAndSendCDR(request, null, producer, CdrMappingUtil::buildStartCDREvent, "s1", "b1");

        verifyNoInteractions(producer); // Should have caught the error in the try-catch block
    }

    // --- Helpers ---

    private AccountingRequestDto mockRequest() {
        AccountingRequestDto req = mock(AccountingRequestDto.class);
        when(req.sessionId()).thenReturn("test-session-id");
        when(req.timestamp()).thenReturn(Instant.now());
        when(req.inputOctets()).thenReturn(0);
        when(req.outputOctets()).thenReturn(0);
        return req;
    }

    private Session mockSession() {
        Session sess = mock(Session.class);
        when(sess.getSessionId()).thenReturn("test-session-id");
        when(sess.getServiceId()).thenReturn("service-99");
        when(sess.getGroupId()).thenReturn("group-1");
        return sess;
    }
}