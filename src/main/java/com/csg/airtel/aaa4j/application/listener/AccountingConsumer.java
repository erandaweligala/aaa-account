package com.csg.airtel.aaa4j.application.listener;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
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
import org.slf4j.MDC;

import java.util.concurrent.Semaphore;

@ApplicationScoped
public class AccountingConsumer {
    private static final Logger LOG = Logger.getLogger(AccountingConsumer.class);
    private static final String METHOD_CONSUME = "consumeAccountingEvent";

    // Backpressure: limit concurrent in-flight async processing to prevent unbounded queue growth
    private static final int MAX_CONCURRENT_PROCESSING = 64;
    private final Semaphore processingLimiter = new Semaphore(MAX_CONCURRENT_PROCESSING);

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

        // Set MDC once on event loop thread — covers all synchronous logs
        setMdcContext(request);
        LoggingUtil.logInfo(LOG, METHOD_CONSUME, "Message received - will acknowledge immediately");

        if (LOG.isDebugEnabled()) {
            message.getMetadata(IncomingKafkaRecordMetadata.class)
                    .ifPresent(metadata -> LoggingUtil.logDebug(LOG, METHOD_CONSUME,
                            "Partition: %d, Offset: %d", metadata.getPartition(), metadata.getOffset()));
        }

        // Acquire permit for backpressure — blocks if too many in-flight messages
        try {
            processingLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LoggingUtil.logError(LOG, METHOD_CONSUME, e,
                    "Interrupted while acquiring processing permit for session: %s", request.sessionId());
            return Uni.createFrom().completionStage(message.ack());
        }

        // Acknowledge immediately, then process asynchronously
        return Uni.createFrom().completionStage(message.ack())
                .onItem().transformToUni(v -> {
                    // Ack callback runs on same event loop thread — MDC already set
                    LoggingUtil.logInfo(LOG, METHOD_CONSUME,
                            "Message acknowledged for session: %s, starting async processing",
                            request.sessionId());

                    // Process in background on worker pool with bounded concurrency
                    accountingHandlerFactory.getHandler(request, request.eventId())
                            .onItem().invoke(success -> {
                                // Worker thread — set MDC once for this thread
                                setMdcContext(request);
                                long duration = System.currentTimeMillis() - startTime;
                                LoggingUtil.logInfo(LOG, METHOD_CONSUME,
                                        "Complete consumeAccountingEvent process in %d ms for session: %s",
                                        duration, request.sessionId());
                                MDC.clear();
                            })
                            .onFailure().invoke(failure -> {
                                // Worker thread — set MDC once for this thread
                                setMdcContext(request);
                                long duration = System.currentTimeMillis() - startTime;
                                LoggingUtil.logError(LOG, METHOD_CONSUME, failure,
                                        "Failed processing session: %s after %d ms",
                                        request.sessionId(), duration);
                                MDC.clear();
                            })
                            .onTermination().invoke(() -> processingLimiter.release())
                            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                            .subscribe().asCompletionStage();

                    return Uni.createFrom().voidItem();
                });
    }


    private void setMdcContext(AccountingRequestDto request) {
        MDC.put(LoggingUtil.TRACE_ID, request.eventId());
        MDC.put("userName", request.username());
        MDC.put("sessionId", request.sessionId());
    }
}

