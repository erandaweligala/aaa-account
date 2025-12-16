package com.csg.airtel.aaa4j.application.listener;


import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;



import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;



@ApplicationScoped
public class AccountingConsumer {
    private static final Logger LOG = Logger.getLogger(AccountingConsumer.class);

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
        LOG.infof("Message received - will acknowledge immediately");

        AccountingRequestDto request = message.getPayload();

        if (LOG.isDebugEnabled()) {
            message.getMetadata(IncomingKafkaRecordMetadata.class)
                    .ifPresent(metadata -> LOG.debugf("Partition: %d, Offset: %d",
                            metadata.getPartition(), metadata.getOffset()));
        }

        // Acknowledge immediately, then process asynchronously
        return Uni.createFrom().completionStage(message.ack())
                .onItem().transformToUni(v -> {
                    LOG.infof("Message acknowledged for session: %s, starting async processing",
                            request.sessionId());

                    // Process in background - don't wait for completion
                    accountingHandlerFactory.getHandler(request, request.eventId())
                            .onItem().invoke(success -> {
                                long duration = System.currentTimeMillis() - startTime;
                                LOG.infof("Complete consumeAccountingEvent process in %d ms for session: %s",
                                        duration, request.sessionId());
                            })
                            .onFailure().invoke(failure -> {
                                long duration = System.currentTimeMillis() - startTime;
                                LOG.errorf(failure, "Failed processing session: %s after %d ms",
                                        request.sessionId(), duration);
                            })
                            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                            .subscribe().asCompletionStage(); //todo Calling 'subscribe' in non-blocking context is not recommended

                    return Uni.createFrom().voidItem();
                });
    }
}

