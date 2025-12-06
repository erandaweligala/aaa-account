package com.csg.airtel.aaa4j.application.listener;


import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
    //todo Calling 'subscribe' in non-blocking context is not recommended
    @Incoming("accounting-events")
    public Uni<Void> consumeAccountingEvent(Message<AccountingRequestDto> message) {
        long startTime = System.currentTimeMillis();
        LOG.infof("Start consumeAccountingEvent process");
        AccountingRequestDto request = message.getPayload();
        if (LOG.isDebugEnabled()) {
            message.getMetadata(IncomingKafkaRecordMetadata.class)
                    .ifPresent(metadata -> LOG.debugf("Partition: %d, Offset: %d",
                            metadata.getPartition(), metadata.getOffset()));
        }

        // ACK immediately upon message receipt - no need to wait for processing to complete
        // This improves throughput by decoupling message consumption from processing
        accountingHandlerFactory.getHandler(request, request.eventId())
                .subscribe().with(
                        v -> {
                            long duration = System.currentTimeMillis() - startTime;
                            LOG.infof("Complete consumeAccountingEvent process %s ms", duration);
                        },
                        e -> LOG.errorf(e, "Failed processing session: %s", request.sessionId())
                );

        return Uni.createFrom().completionStage(message.ack());
    }
}

