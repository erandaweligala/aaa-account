package com.csg.airtel.aaa4j.application.listener;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

@ApplicationScoped
public class AccountingConsumer {
    private static final Logger LOG = Logger.getLogger(AccountingConsumer.class);
    private static final String METHOD_CONSUME = "consumeAccountingEvent";

    final AccountingHandlerFactory accountingHandlerFactory;

    @Inject
    public AccountingConsumer(AccountingHandlerFactory accountingHandlerFactory) {
        this.accountingHandlerFactory = accountingHandlerFactory;
    }

    /**
     * Consumes accounting events with backpressure-aware processing.
     * SmallRye's concurrency setting (16) controls how many messages are in-flight,
     * naturally throttling poll rate when processing is slower than ingestion.
     * All downstream operations (Redis, Kafka) are non-blocking reactive I/O,
     * so no worker pool offload is needed - avoiding thread context switch overhead.
     */
    @Incoming("accounting-events")
    @Acknowledgment(Acknowledgment.Strategy.PRE_PROCESSING)
    public Uni<Void> consumeAccountingEvent(Message<AccountingRequestDto> message) {
        AccountingRequestDto request = message.getPayload();

        setMdcContext(request);
        if (LOG.isDebugEnabled()) {
            message.getMetadata(IncomingKafkaRecordMetadata.class)
                    .ifPresent(metadata -> LoggingUtil.logDebug(LOG, METHOD_CONSUME,
                            "Partition: %d, Offset: %d, session: %s",
                            metadata.getPartition(), metadata.getOffset(), request.sessionId()));
        }

        return accountingHandlerFactory.getHandler(request, request.eventId())
                .onFailure().recoverWithUni(failure -> {
                    LoggingUtil.logError(LOG, METHOD_CONSUME, failure,
                            "Failed processing session: %s", request.sessionId());
                    return Uni.createFrom().voidItem();
                });
    }

    private void setMdcContext(AccountingRequestDto request) {
        MDC.put(LoggingUtil.TRACE_ID, request.eventId());
        MDC.put("userName", request.username());
        MDC.put("sessionId", request.sessionId());
    }
}

