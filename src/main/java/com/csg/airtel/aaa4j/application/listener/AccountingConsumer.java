package com.csg.airtel.aaa4j.application.listener;


import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import com.csg.airtel.aaa4j.domain.service.FailoverPathLogger;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.reactive.messaging.Incoming;



import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;



@ApplicationScoped
public class AccountingConsumer {
    private static final Logger LOG = Logger.getLogger(AccountingConsumer.class);

    final AccountProducer accountingProdEvent;
    final AccountingHandlerFactory accountingHandlerFactory;

    // Failover path tracking
    private final ThreadLocal<FailoverPathLogger.FailureCounter> consumeCounter =
            ThreadLocal.withInitial(() -> new FailoverPathLogger.FailureCounter("KAFKA_CONSUME_ACCOUNTING"));

    @Inject
    public AccountingConsumer(AccountProducer accountingProdEvent, AccountingHandlerFactory accountingHandlerFactory) {
        this.accountingProdEvent = accountingProdEvent;
        this.accountingHandlerFactory = accountingHandlerFactory;
    }

    @Incoming("accounting-events")
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 10000, successThreshold = 3)
    @Retry(maxRetries = 2, delay = 500, maxDuration = 15000)
    @Timeout(value = 30000)
    public Uni<Void> consumeAccountingEvent(Message<AccountingRequestDto> message) {
        long startTime = System.currentTimeMillis();
        FailoverPathLogger.FailureCounter counter = consumeCounter.get();
        int attemptCount = counter.incrementAttempt();

        AccountingRequestDto request = message.getPayload();
        FailoverPathLogger.logPrimaryPathAttempt(LOG, counter.getPath(), request.sessionId());
        LOG.infof("Start consumeAccountingEvent process");
        if (LOG.isDebugEnabled()) {
            message.getMetadata(IncomingKafkaRecordMetadata.class)
                    .ifPresent(metadata -> LOG.debugf("Partition: %d, Offset: %d",
                            metadata.getPartition(), metadata.getOffset()));
        }
        return accountingHandlerFactory.getHandler(request,request.eventId())
                .onItem().transformToUni(v ->{
                    if (counter.getFailureCount() > 0) {
                        FailoverPathLogger.logSuccessAfterFailure(LOG, counter.getPath(), attemptCount, request.sessionId());
                    }
                    counter.reset();
                    long duration = System.currentTimeMillis() - startTime;
                    LOG.infof("Complete consumeAccountingEvent process %s ms",duration);
                  return  Uni.createFrom().completionStage(message.ack());
                })
                .onFailure().recoverWithUni(e -> {
                    int failCount = counter.incrementFailure();
                    FailoverPathLogger.logFailoverAttempt(LOG, counter.getPath(), attemptCount, failCount, request.sessionId(), e);
                    LOG.errorf(e, "Failed processing session: %s", request.sessionId());
                    return Uni.createFrom().completionStage(message.nack(e));
                });
    }
}

