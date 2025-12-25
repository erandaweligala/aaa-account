package com.csg.airtel.aaa4j.application.listener;


import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import com.csg.airtel.aaa4j.domain.util.StructuredLogger;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;



import org.eclipse.microprofile.reactive.messaging.Message;



@ApplicationScoped
public class AccountingConsumer {
    private static final StructuredLogger LOG = StructuredLogger.getLogger(AccountingConsumer.class);

    final AccountProducer accountingProdEvent;
    final AccountingHandlerFactory accountingHandlerFactory;

    @Inject
    public AccountingConsumer(AccountProducer accountingProdEvent, AccountingHandlerFactory accountingHandlerFactory) {
        this.accountingProdEvent = accountingProdEvent;
        this.accountingHandlerFactory = accountingHandlerFactory;
    }

    @Incoming("accounting-events")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> consumeAccountingEvent(Message<AccountingRequestDto> message) {
        long startTime = System.currentTimeMillis();

        AccountingRequestDto request = message.getPayload();

        // Set MDC context for Kafka message processing
        StructuredLogger.setContext(request.eventId(), request.username(), request.sessionId());
        StructuredLogger.setOperation("KAFKA_CONSUME");

        // Log Kafka metadata if debug enabled
        if (LOG.isDebugEnabled()) {
            message.getMetadata(IncomingKafkaRecordMetadata.class)
                    .ifPresent(metadata -> LOG.debug("Kafka message received", StructuredLogger.Fields.create()
                            .add("partition", metadata.getPartition())
                            .add("offset", metadata.getOffset())
                            .add("topic", metadata.getTopic())
                            .add("username", request.username())
                            .add("sessionId", request.sessionId())
                            .add("acctStatusType", request.actionType())
                            .build()));
        }

        LOG.info("Kafka message received, acknowledging immediately", StructuredLogger.Fields.create()
                .add("username", request.username())
                .add("sessionId", request.sessionId())
                .add("acctStatusType", request.actionType())
                .add("eventId", request.eventId())
                .build());

        // Acknowledge immediately, then process asynchronously
        return Uni.createFrom().completionStage(message.ack())
                .onItem().transformToUni(v -> {
                    LOG.info("Message acknowledged, starting async processing", StructuredLogger.Fields.create()
                            .add("sessionId", request.sessionId())
                            .build());

                    // Process in background - don't wait for completion
                    accountingHandlerFactory.getHandler(request, request.eventId())
                            .onItem().invoke(success -> {
                                long duration = System.currentTimeMillis() - startTime;
                                LOG.info("Completed Kafka message processing", StructuredLogger.Fields.create()
                                        .add("sessionId", request.sessionId())
                                        .add("username", request.username())
                                        .addDuration(duration)
                                        .addStatus("success")
                                        .addComponent("kafka-consumer")
                                        .build());
                            })
                            .onFailure().invoke(failure -> {
                                long duration = System.currentTimeMillis() - startTime;
                                LOG.error("Failed Kafka message processing", failure, StructuredLogger.Fields.create()
                                        .add("sessionId", request.sessionId())
                                        .add("username", request.username())
                                        .addDuration(duration)
                                        .addStatus("failed")
                                        .addErrorCode("KAFKA_PROCESSING_ERROR")
                                        .addComponent("kafka-consumer")
                                        .add("errorType", failure.getClass().getSimpleName())
                                        .build());
                            })
                            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                            .subscribe().asCompletionStage();

                    StructuredLogger.clearContext();
                    return Uni.createFrom().voidItem();
                });
    }
}

