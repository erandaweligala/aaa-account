package com.csg.airtel.aaa4j.application.listener;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.service.AccountingHandlerFactory;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class AccountingConsumer {
    private static final Logger LOG = Logger.getLogger(AccountingConsumer.class);
    private static final String METHOD_CONSUME = "consumeAccountingEvent";
    private static final String METHOD_TPS     = "tps";
    private static final int    TPS_WINDOW_SEC = 10;

    private final AtomicLong totalConsumed   = new AtomicLong(0);
    private final AtomicLong lastSnapshot    = new AtomicLong(0);

    final AccountingHandlerFactory accountingHandlerFactory;

    @Inject
    public AccountingConsumer(AccountingHandlerFactory accountingHandlerFactory) {
        this.accountingHandlerFactory = accountingHandlerFactory;
    }

    /**
     * Consumes accounting events with backpressure-aware processing.
     * Flow: process message → ack on completion → SmallRye commits offset.
     * SmallRye's concurrency setting (16) controls how many messages are in-flight,
     * naturally throttling poll rate when processing is slower than ingestion.
     * This prevents unbounded queue buildup and OOM on 2GB pods.
     */
    @Incoming("accounting-events")
    @Acknowledgment(Acknowledgment.Strategy.PRE_PROCESSING)
    public Uni<Void> consumeAccountingEvent(Message<AccountingRequestDto> message) {
        AccountingRequestDto request = message.getPayload();

        setMdcContext(request);
        totalConsumed.incrementAndGet();

        return accountingHandlerFactory.getHandler(request, request.eventId())
                .onFailure().recoverWithUni(failure -> {
                    LoggingUtil.logError(LOG, METHOD_CONSUME, failure,
                            "Failed processing session: %s", request.sessionId());
                    return Uni.createFrom().voidItem();
                })
                .onTermination().invoke(this::clearMdcContext);
    }

    @Scheduled(every = "10s")
    void logAvgTps() {
        long current  = totalConsumed.get();
        long previous = lastSnapshot.getAndSet(current);
        double tps    = (current - previous) / (double) TPS_WINDOW_SEC;
        LoggingUtil.logInfo(LOG, METHOD_TPS, "avg_tps=%.2f window=%ds total=%d", tps, TPS_WINDOW_SEC, current);
    }

    private void setMdcContext(AccountingRequestDto request) {
        MDC.put(LoggingUtil.TRACE_ID,   nvl(request.eventId(),   "no-event-id"));
        MDC.put(LoggingUtil.USER_NAME,  nvl(request.username(),  "unknown"));
        MDC.put(LoggingUtil.SESSION_ID, nvl(request.sessionId(), "no-session"));
    }

    private String nvl(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private void clearMdcContext() {
        MDC.remove(LoggingUtil.TRACE_ID);
        MDC.remove(LoggingUtil.USER_NAME);
        MDC.remove(LoggingUtil.SESSION_ID);
    }
}
