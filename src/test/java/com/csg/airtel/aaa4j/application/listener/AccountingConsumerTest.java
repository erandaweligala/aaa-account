package com.csg.airtel.aaa4j.application.listener;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.ProcessType;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountingConsumerTest {

    @Mock
    private AccountProducer accountingProdEvent;

    @Mock
    private AccountingHandlerFactory accountingHandlerFactory;

    @Mock
    private Message<AccountingRequestDto> message;

    private AccountingConsumer accountingConsumer;

    @BeforeEach
    void setUp() {
        accountingConsumer = new AccountingConsumer(accountingProdEvent, accountingHandlerFactory);
    }

    @Test
    void testConsumeAccountingEventSuccess() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START);
        CompletionStage<Void> ackStage = CompletableFuture.completedFuture(null);

        when(message.getPayload()).thenReturn(request);
        when(message.getMetadata()).thenReturn(Metadata.empty());
        when(message.ack()).thenReturn(ackStage);
        when(accountingHandlerFactory.getHandler(eq(request), eq(request.eventId())))
                .thenReturn(Uni.createFrom().voidItem());

        accountingConsumer.consumeAccountingEvent(message)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(message, times(1)).getPayload();
        verify(accountingHandlerFactory, times(1)).getHandler(eq(request), eq(request.eventId()));
        verify(message, times(1)).ack();
        verify(message, never()).nack(any());
    }

    @Test
    void testConsumeAccountingEventWithStartAction() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START);
        CompletionStage<Void> ackStage = CompletableFuture.completedFuture(null);

        when(message.getPayload()).thenReturn(request);
        when(message.getMetadata()).thenReturn(Metadata.empty());
        when(message.ack()).thenReturn(ackStage);
        when(accountingHandlerFactory.getHandler(eq(request), eq(request.eventId())))
                .thenReturn(Uni.createFrom().voidItem());

        accountingConsumer.consumeAccountingEvent(message)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingHandlerFactory, times(1)).getHandler(eq(request), eq(request.eventId()));
        verify(message, times(1)).ack();
    }

    @Test
    void testConsumeAccountingEventWithInterimAction() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.INTERIM_UPDATE);
        CompletionStage<Void> ackStage = CompletableFuture.completedFuture(null);

        when(message.getPayload()).thenReturn(request);
        when(message.getMetadata()).thenReturn(Metadata.empty());
        when(message.ack()).thenReturn(ackStage);
        when(accountingHandlerFactory.getHandler(eq(request), eq(request.eventId())))
                .thenReturn(Uni.createFrom().voidItem());

        accountingConsumer.consumeAccountingEvent(message)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingHandlerFactory, times(1)).getHandler(eq(request), eq(request.eventId()));
        verify(message, times(1)).ack();
    }

    @Test
    void testConsumeAccountingEventWithStopAction() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.STOP);
        CompletionStage<Void> ackStage = CompletableFuture.completedFuture(null);

        when(message.getPayload()).thenReturn(request);
        when(message.getMetadata()).thenReturn(Metadata.empty());
        when(message.ack()).thenReturn(ackStage);
        when(accountingHandlerFactory.getHandler(eq(request), eq(request.eventId())))
                .thenReturn(Uni.createFrom().voidItem());

        accountingConsumer.consumeAccountingEvent(message)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingHandlerFactory, times(1)).getHandler(eq(request), eq(request.eventId()));
        verify(message, times(1)).ack();
    }

    @Test
    void testConsumeAccountingEventWithFailure() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START);
        RuntimeException exception = new RuntimeException("Processing failed");
        CompletionStage<Void> nackStage = CompletableFuture.completedFuture(null);

        when(message.getPayload()).thenReturn(request);
        when(message.getMetadata()).thenReturn(Metadata.empty());
        when(message.nack(any())).thenReturn(nackStage);
        when(accountingHandlerFactory.getHandler(eq(request), eq(request.eventId())))
                .thenReturn(Uni.createFrom().failure(exception));

        accountingConsumer.consumeAccountingEvent(message)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(accountingHandlerFactory, times(1)).getHandler(eq(request), eq(request.eventId()));
        verify(message, times(1)).nack(exception);
        verify(message, never()).ack();
    }

    @Test
    void testConsumeAccountingEventWithKafkaMetadata() {
        AccountingRequestDto request = createAccountingRequest(ProcessType.START);
        CompletionStage<Void> ackStage = CompletableFuture.completedFuture(null);
        IncomingKafkaRecordMetadata<String> kafkaMetadata = mock(IncomingKafkaRecordMetadata.class);

        when(kafkaMetadata.getPartition()).thenReturn(0);
        when(kafkaMetadata.getOffset()).thenReturn(100L);
        when(message.getPayload()).thenReturn(request);
        when(message.getMetadata()).thenReturn(Metadata.of(kafkaMetadata));
        when(message.getMetadata(IncomingKafkaRecordMetadata.class)).thenReturn(java.util.Optional.of(kafkaMetadata));
        when(message.ack()).thenReturn(ackStage);
        when(accountingHandlerFactory.getHandler(eq(request), eq(request.eventId())))
                .thenReturn(Uni.createFrom().voidItem());

        accountingConsumer.consumeAccountingEvent(message)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(message, times(1)).ack();
    }

    @Test
    void testConsumeAccountingEventMultipleTimes() {
        AccountingRequestDto request1 = createAccountingRequest(ProcessType.START);
        AccountingRequestDto request2 = createAccountingRequest(ProcessType.INTERIM_UPDATE);
        CompletionStage<Void> ackStage = CompletableFuture.completedFuture(null);

        Message<AccountingRequestDto> message1 = mock(Message.class);
        Message<AccountingRequestDto> message2 = mock(Message.class);

        when(message1.getPayload()).thenReturn(request1);
        when(message1.getMetadata()).thenReturn(Metadata.empty());
        when(message1.ack()).thenReturn(ackStage);

        when(message2.getPayload()).thenReturn(request2);
        when(message2.getMetadata()).thenReturn(Metadata.empty());
        when(message2.ack()).thenReturn(ackStage);

        when(accountingHandlerFactory.getHandler(eq(request1), eq(request1.eventId())))
                .thenReturn(Uni.createFrom().voidItem());
        when(accountingHandlerFactory.getHandler(eq(request2), eq(request2.eventId())))
                .thenReturn(Uni.createFrom().voidItem());

        accountingConsumer.consumeAccountingEvent(message1)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        accountingConsumer.consumeAccountingEvent(message2)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        verify(message1, times(1)).ack();
        verify(message2, times(1)).ack();
    }

    private AccountingRequestDto createAccountingRequest(ProcessType actionType) {
        return new AccountingRequestDto(
                "event-id-123",
                "session-id-123",
                "test-user",
                "192.168.1.1",
                "10.0.0.1",
                "NAS-1",
                "NAS-PORT-1",
                actionType,
                Instant.now(),
                100,
                1000,
                2000,
                1,
                2
        );
    }
}
