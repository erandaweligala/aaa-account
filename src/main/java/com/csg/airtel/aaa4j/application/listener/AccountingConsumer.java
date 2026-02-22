package com.csg.airtel.aaa4j.application.listener;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

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
     *
     * Flow: process message → ack on completion → SmallRye commits offset.
     * SmallRye's concurrency setting (16) controls how many messages are in-flight,
     * naturally throttling poll rate when processing is slower than ingestion.
     * This prevents unbounded queue buildup and OOM on 2GB pods.
     */
    @Incoming("accounting-events")
    @Acknowledgment(Acknowledgment.Strategy.PRE_PROCESSING)
    public Uni<Void> consumeAccountingEvent(Message<AccountingRequestDto> message) {
        AccountingRequestDto request = message.getPayload();

        return accountingHandlerFactory.getHandler(request, request.eventId())
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().recoverWithUni(failure -> {
                    LoggingUtil.logError(LOG, METHOD_CONSUME, failure,
                            "Failed processing session: %s", request.sessionId());
                    return Uni.createFrom().voidItem();
                })
                .chain(() -> Uni.createFrom().completionStage(message.ack()));
    }
}

