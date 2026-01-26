package com.csg.airtel.aaa4j.domain.produce;

import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.EventType;
import com.csg.airtel.aaa4j.domain.model.QuotaNotificationEvent;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.service.SessionLifecycleManager;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountProducerTest {

    @Mock
    private Emitter<DBWriteRequest> dbWriteRequestEmitter;

    @Mock
    private Emitter<AccountingResponseEvent> accountingResponseEmitter;

    @Mock
    private Emitter<AccountingCDREvent> accountingCDREventEmitter;

    @Mock
    private Emitter<QuotaNotificationEvent> quotaNotificationEmitter;

    @Mock
    private CacheClient cacheClient;

    @Mock
    private SessionLifecycleManager sessionLifecycleManager;

    private AccountProducer accountProducer;

    @BeforeEach
    void setUp() {
        accountProducer = new AccountProducer(
            dbWriteRequestEmitter,
            accountingResponseEmitter,
            accountingCDREventEmitter,
            quotaNotificationEmitter,
            cacheClient,
            sessionLifecycleManager
        );
    }

    @Test
    void testProduceDBWriteEvent_Success() {
        DBWriteRequest request = createDBWriteRequest();

        ArgumentCaptor<Message<DBWriteRequest>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        doAnswer(invocation -> {
            Message<DBWriteRequest> msg = invocation.getArgument(0);
            msg.getAck().get().run();
            return null;
        }).when(dbWriteRequestEmitter).send(any());

        accountProducer.produceDBWriteEvent(request)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(dbWriteRequestEmitter).send(messageCaptor.capture());
        Message<DBWriteRequest> sentMessage = messageCaptor.getValue();
        assertNotNull(sentMessage);
        assertEquals(request, sentMessage.getPayload());
    }

    @Test
    void testProduceAccountingResponseEvent_Success() {
        AccountingResponseEvent event = createAccountingResponseEvent();

        ArgumentCaptor<Message<AccountingResponseEvent>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        doAnswer(invocation -> {
            Message<AccountingResponseEvent> msg = invocation.getArgument(0);
            msg.getAck().get().run();
            return null;
        }).when(accountingResponseEmitter).send(any());

        accountProducer.produceAccountingResponseEvent(event)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(accountingResponseEmitter).send(messageCaptor.capture());
        Message<AccountingResponseEvent> sentMessage = messageCaptor.getValue();
        assertNotNull(sentMessage);
        assertEquals(event, sentMessage.getPayload());
    }

    @Test
    void testProduceQuotaNotificationEvent_Success() {
        QuotaNotificationEvent event = createQuotaNotificationEvent();

        ArgumentCaptor<Message<QuotaNotificationEvent>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        doAnswer(invocation -> {
            Message<QuotaNotificationEvent> msg = invocation.getArgument(0);
            msg.getAck().get().run();
            return null;
        }).when(quotaNotificationEmitter).send(any());

        accountProducer.produceQuotaNotificationEvent(event)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(quotaNotificationEmitter).send(messageCaptor.capture());
        Message<QuotaNotificationEvent> sentMessage = messageCaptor.getValue();
        assertNotNull(sentMessage);
        assertEquals(event, sentMessage.getPayload());
    }

    private DBWriteRequest createDBWriteRequest() {
        DBWriteRequest request = new DBWriteRequest();
        request.setSessionId("session-123");
        request.setUserName("testuser");
        request.setEventType(EventType.UPDATE_EVENT);
        request.setTableName("test_table");
        request.setTimestamp(LocalDateTime.now());
        return request;
    }

    private AccountingResponseEvent createAccountingResponseEvent() {
        return new AccountingResponseEvent(
            AccountingResponseEvent.EventType.COA,
            LocalDateTime.now(),
            "session-123",
            AccountingResponseEvent.ResponseAction.DISCONNECT,
            "Test message",
            0L,
            java.util.Map.of()
        );
    }

    private QuotaNotificationEvent createQuotaNotificationEvent() {
        return new QuotaNotificationEvent(
            LocalDateTime.now(),
            "Test notification",
            "testuser",
            "80% quota exceeded",
            200L,
            "bucket-123",
            80L,
            1000L,
            100L
        );
    }
}
