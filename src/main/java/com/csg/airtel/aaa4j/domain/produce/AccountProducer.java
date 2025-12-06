package com.csg.airtel.aaa4j.domain.produce;

import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.service.FailoverPathLogger;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;



@ApplicationScoped
public class AccountProducer {

    private static final Logger LOG = Logger.getLogger(AccountProducer.class);
    Emitter<DBWriteRequest> dbWriteRequestEmitter;
    Emitter<AccountingResponseEvent> accountingResponseEmitter;
    Emitter<AccountingCDREvent> accountingCDREventEmitter;

    public AccountProducer(@Channel("db-write-events")Emitter<DBWriteRequest> dbWriteRequestEmitter,
                           @Channel("accounting-resp-events")Emitter<AccountingResponseEvent> accountingResponseEmitter,
                           @Channel("accounting-cdr-events") Emitter<AccountingCDREvent> accountingCDREventEmitter
                          ) {
        this.dbWriteRequestEmitter = dbWriteRequestEmitter;
        this.accountingResponseEmitter = accountingResponseEmitter;
        this.accountingCDREventEmitter = accountingCDREventEmitter;

    }

    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 3,
            delay = 100,
            maxDuration = 10000
    )
    @Timeout(value = 10000)
    @Fallback(fallbackMethod = "fallbackProduceDBWriteEvent")
    public Uni<Void> produceDBWriteEvent(DBWriteRequest request) {
        long startTime = System.currentTimeMillis();
        LOG.infof("Start produceDBWriteEvent process Started sessionId : %s", request.getSessionId());
        return Uni.createFrom().emitter(em -> {
            Message<DBWriteRequest> message = Message.of(request)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(request.getSessionId())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        LOG.infof("Successfully sent accounting DB create event for session: %s, %d ms", request.getSessionId(),System.currentTimeMillis()-startTime);
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        LOG.errorf("Send failed: %s", throwable.getMessage());
                        em.fail(throwable);
                        return CompletableFuture.completedFuture(null);
                    });

            dbWriteRequestEmitter.send(message);
        });
    }

    /**
     * @param event request
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 3,
            delay = 100,
            maxDuration = 10000
    )
    @Timeout(value = 10000)
    @Fallback(fallbackMethod = "fallbackProduceAccountingResponseEvent")
    public Uni<Void> produceAccountingResponseEvent(AccountingResponseEvent event) {
        long startTime = System.currentTimeMillis();
        LOG.infof("Start produceAccountingResponseEvent process");
        return Uni.createFrom().emitter(em -> {
            Message<AccountingResponseEvent> message = Message.of(event)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(event.sessionId())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        LOG.infof("Successfully sent accounting response event for session: %s, %d ms", event.sessionId(),System.currentTimeMillis()-startTime);
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        LOG.errorf("Send failed: %s", throwable.getMessage());
                        em.fail(throwable);
                        return CompletableFuture.completedFuture(null);
                    });

            accountingResponseEmitter.send(message);
        });
    }

    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 3,
            delay = 100,
            maxDuration = 10000
    )
    @Timeout(value = 10000)
    @Fallback(fallbackMethod = "fallbackProduceAccountingCDREvent")
    public Uni<Void> produceAccountingCDREvent(AccountingCDREvent event) {
        long startTime = System.currentTimeMillis();
        LOG.infof("Start produce Accounting CDR Event process");
        return Uni.createFrom().emitter(em -> {
            Message<AccountingCDREvent> message = Message.of(event)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(event.getPayload().getSession().getSessionId())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        LOG.infof("Successfully sent accounting CDR event for session: %s, %d ms", event.getPayload().getSession().getSessionId(),System.currentTimeMillis()-startTime);
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        LOG.errorf("CDR Send failed: %s", throwable.getMessage());
                        em.fail(throwable);
                        return CompletableFuture.completedFuture(null);
                    });

            accountingCDREventEmitter.send(message);
        });

    }

    /**
     * Fallback method for produceDBWriteEvent.
     *
     * @param request the DB write request
     * @param throwable the failure cause
     * @return empty Uni (operation failed)
     */
    //todo need to implement fallback path revoke to session clear
    private Uni<Void> fallbackProduceDBWriteEvent(DBWriteRequest request, Throwable throwable) {
        FailoverPathLogger.logFallbackPath(LOG, "produceDBWriteEvent", request.getSessionId(), throwable);
        return Uni.createFrom().voidItem();
    }

    /**
     * Fallback method for produceAccountingResponseEvent.
     *
     * @param event the accounting response event
     * @param throwable the failure cause
     * @return empty Uni (operation failed)
     */
    private Uni<Void> fallbackProduceAccountingResponseEvent(AccountingResponseEvent event, Throwable throwable) {
        FailoverPathLogger.logFallbackPath(LOG, "produceAccountingResponseEvent", event.sessionId(), throwable);
        return Uni.createFrom().voidItem();
    }

    /**
     * Fallback method for produceAccountingCDREvent.
     *
     * @param event the accounting CDR event
     * @param throwable the failure cause
     * @return empty Uni (operation failed)
     */
    private Uni<Void> fallbackProduceAccountingCDREvent(AccountingCDREvent event, Throwable throwable) {
        String sessionId = event.getPayload() != null && event.getPayload().getSession() != null
                ? event.getPayload().getSession().getSessionId()
                : "unknown";
        FailoverPathLogger.logFallbackPath(LOG, "produceAccountingCDREvent", sessionId, throwable);
        return Uni.createFrom().voidItem();
    }

}
