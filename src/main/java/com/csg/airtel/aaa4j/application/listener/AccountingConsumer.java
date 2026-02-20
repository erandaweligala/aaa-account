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

@ApplicationScoped
public class AccountingConsumer {
    private static final Logger LOG = Logger.getLogger(AccountingConsumer.class);
    private static final String METHOD_CONSUME = "consumeAccountingEvent";

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

        if (LOG.isDebugEnabled()) {
            message.getMetadata(IncomingKafkaRecordMetadata.class)
                    .ifPresent(metadata -> LoggingUtil.logDebug(LOG, METHOD_CONSUME,
                            "Partition: %d, Offset: %d, session: %s",
                            metadata.getPartition(), metadata.getOffset(), request.sessionId()));
        }
        
        return Uni.createFrom().completionStage(message.ack())
                .onItem().transformToUni(v ->
                    accountingHandlerFactory.getHandler(request, request.eventId())
                            .onItem().invoke(success -> {
                                setMdcContext(request);
                                long duration = System.currentTimeMillis() - startTime;
                                LoggingUtil.logInfo(LOG, METHOD_CONSUME,
                                        "Complete consumeAccountingEvent process in %d ms for session: %s",
                                        duration, request.sessionId());
                                MDC.clear();
                            })
                            .onFailure().recoverWithUni(failure -> {
                                setMdcContext(request);
                                long duration = System.currentTimeMillis() - startTime;
                                LoggingUtil.logError(LOG, METHOD_CONSUME, failure,
                                        "Failed processing session: %s after %d ms",
                                        request.sessionId(), duration);
                                MDC.clear();
                                return Uni.createFrom().voidItem();
                            })
                            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                );
    }


    private void setMdcContext(AccountingRequestDto request) {
        MDC.put(LoggingUtil.TRACE_ID, request.eventId());
        MDC.put("userName", request.username());
        MDC.put("sessionId", request.sessionId());
    }
}

