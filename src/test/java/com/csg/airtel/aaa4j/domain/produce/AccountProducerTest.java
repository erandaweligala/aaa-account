package com.csg.airtel.aaa4j.domain.produce;

import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.EventType;
import com.csg.airtel.aaa4j.domain.model.cdr.*;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    @Captor
    private ArgumentCaptor<Message<DBWriteRequest>> dbWriteMessageCaptor;

    @Captor
    private ArgumentCaptor<Message<AccountingResponseEvent>> accountingResponseMessageCaptor;

    @Captor
    private ArgumentCaptor<Message<AccountingCDREvent>> accountingCDRMessageCaptor;

    private AccountProducer accountProducer;

    @BeforeEach
    void setUp() {
        accountProducer = new AccountProducer(
                dbWriteRequestEmitter,
                accountingResponseEmitter,
                accountingCDREventEmitter
        );
    }

    @Test
    void testProduceDBWriteEventSuccess() {
        DBWriteRequest request = createDBWriteRequest();

        doAnswer(invocation -> {
            Message<DBWriteRequest> message = invocation.getArgument(0);
            message.getAck().get().run();
            return null;
        }).when(dbWriteRequestEmitter).send(any());

        accountProducer.produceDBWriteEvent(request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(dbWriteRequestEmitter, times(1)).send(dbWriteMessageCaptor.capture());
        Message<DBWriteRequest> capturedMessage = dbWriteMessageCaptor.getValue();
        assertEquals(request, capturedMessage.getPayload());
    }

    @Test
    void testProduceDBWriteEventFailure() {
        DBWriteRequest request = createDBWriteRequest();

        doAnswer(invocation -> {
            Message<DBWriteRequest> message = invocation.getArgument(0);
            message.getNack().get().apply(new RuntimeException("Send failed"));
            return null;
        }).when(dbWriteRequestEmitter).send(any());

        accountProducer.produceDBWriteEvent(request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(RuntimeException.class);

        verify(dbWriteRequestEmitter, times(1)).send(any());
    }

    @Test
    void testProduceAccountingResponseEventSuccess() {
        AccountingResponseEvent event = createAccountingResponseEvent();

        doAnswer(invocation -> {
            Message<AccountingResponseEvent> message = invocation.getArgument(0);
            message.getAck().get().run();
            return null;
        }).when(accountingResponseEmitter).send(any());

        accountProducer.produceAccountingResponseEvent(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingResponseEmitter, times(1)).send(accountingResponseMessageCaptor.capture());
        Message<AccountingResponseEvent> capturedMessage = accountingResponseMessageCaptor.getValue();
        assertEquals(event, capturedMessage.getPayload());
    }

    @Test
    void testProduceAccountingResponseEventFailure() {
        AccountingResponseEvent event = createAccountingResponseEvent();

        doAnswer(invocation -> {
            Message<AccountingResponseEvent> message = invocation.getArgument(0);
            message.getNack().get().apply(new RuntimeException("Send failed"));
            return null;
        }).when(accountingResponseEmitter).send(any());

        accountProducer.produceAccountingResponseEvent(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(RuntimeException.class);

        verify(accountingResponseEmitter, times(1)).send(any());
    }

    @Test
    void testProduceAccountingCDREventSuccess() {
        AccountingCDREvent event = createAccountingCDREvent();

        doAnswer(invocation -> {
            Message<AccountingCDREvent> message = invocation.getArgument(0);
            message.getAck().get().run();
            return null;
        }).when(accountingCDREventEmitter).send(any());

        accountProducer.produceAccountingCDREvent(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingCDREventEmitter, times(1)).send(accountingCDRMessageCaptor.capture());
        Message<AccountingCDREvent> capturedMessage = accountingCDRMessageCaptor.getValue();
        assertEquals(event, capturedMessage.getPayload());
    }

    @Test
    void testProduceAccountingCDREventFailure() {
        AccountingCDREvent event = createAccountingCDREvent();

        doAnswer(invocation -> {
            Message<AccountingCDREvent> message = invocation.getArgument(0);
            message.getNack().get().apply(new RuntimeException("CDR Send failed"));
            return null;
        }).when(accountingCDREventEmitter).send(any());

        accountProducer.produceAccountingCDREvent(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(RuntimeException.class);

        verify(accountingCDREventEmitter, times(1)).send(any());
    }

    @Test
    void testProduceDBWriteEventWithMessageMetadata() {
        DBWriteRequest request = createDBWriteRequest();

        doAnswer(invocation -> {
            Message<DBWriteRequest> message = invocation.getArgument(0);
            assertNotNull(message.getMetadata());
            message.getAck().get().run();
            return null;
        }).when(dbWriteRequestEmitter).send(any());

        accountProducer.produceDBWriteEvent(request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(dbWriteRequestEmitter, times(1)).send(any());
    }

    private DBWriteRequest createDBWriteRequest() {
        DBWriteRequest request = new DBWriteRequest();
        request.setSessionId("session-123");
        request.setUserName("test-user");
        request.setEventType(EventType.UPDATE_EVENT);
        request.setTableName("BUCKET_INSTANCE");
        request.setEventId(UUID.randomUUID().toString());
        request.setTimestamp(LocalDateTime.now());

        Map<String, Object> columnValues = new HashMap<>();
        columnValues.put("CURRENT_BALANCE", 5000L);
        columnValues.put("USAGE", 5000L);
        request.setColumnValues(columnValues);

        Map<String, Object> whereConditions = new HashMap<>();
        whereConditions.put("SERVICE_ID", 1L);
        whereConditions.put("ID", "BUCKET-1");
        request.setWhereConditions(whereConditions);

        return request;
    }

    private AccountingResponseEvent createAccountingResponseEvent() {
        return new AccountingResponseEvent(
                "event-123",
                "session-123",
                "test-user",
                AccountingResponseEvent.EventType.COA,
                AccountingResponseEvent.ResponseAction.DISCONNECT,
                "Test message",
                null,
                Instant.now()
        );
    }

    private AccountingCDREvent createAccountingCDREvent() {
        SessionCdr sessionCdr = SessionCdr.builder()
                .sessionId("session-123")
                .sessionTime("100")
                .startTime(Instant.now())
                .updateTime(Instant.now())
                .nasIdentifier("NAS-1")
                .nasIpAddress("10.0.0.1")
                .nasPort("PORT-1")
                .nasPortType("PORT-1")
                .build();

        User user = User.builder()
                .userName("test-user")
                .build();

        Network network = Network.builder()
                .framedIpAddress("192.168.1.1")
                .calledStationId("10.0.0.1")
                .build();

        Accounting accounting = Accounting.builder()
                .acctStatusType("Start")
                .acctSessionTime(0)
                .acctInputOctets(0L)
                .acctOutputOctets(0L)
                .acctInputPackets(0)
                .acctOutputPackets(0)
                .acctInputGigawords(0)
                .acctOutputGigawords(0)
                .build();

        Payload payload = Payload.builder()
                .session(sessionCdr)
                .user(user)
                .network(network)
                .accounting(accounting)
                .build();

        return AccountingCDREvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventTypes.ACCOUNTING_START.name())
                .eventVersion("1.0")
                .eventTimestamp(Instant.now())
                .source("AAA-Service")
                .payload(payload)
                .build();
    }
}
