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
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class AccountingConsumer {
    private static final Logger LOG = Logger.getLogger(AccountingConsumer.class);
    private static final String METHOD_CONSUME = "consumeAccountingEvent";
    private static final int PROGRESS_INTERVAL = 1000;

    private final AtomicLong totalProcessed   = new AtomicLong(0);
    private final AtomicLong lastProgressCount = new AtomicLong(0);
    private final AtomicLong lastProgressTime  = new AtomicLong(System.currentTimeMillis());
    private final Instant startTime = Instant.now();

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

        return accountingHandlerFactory.getHandler(request, request.eventId())
                .onFailure().recoverWithUni(failure -> {
                    LoggingUtil.logError(LOG, METHOD_CONSUME, failure,
                            "Failed processing session: %s", request.sessionId());
                    return Uni.createFrom().voidItem();
                })
                .onTermination().invoke(() -> {
                    logTpsProgress();
                    clearMdcContext();
                });
    }

    private void logTpsProgress() {
        long total = totalProcessed.incrementAndGet();
        if (total - lastProgressCount.get() >= PROGRESS_INTERVAL) {
            long currentTime = System.currentTimeMillis();
            long elapsed = currentTime - lastProgressTime.get();
            long recordsSinceLastLog = total - lastProgressCount.get();
            double tps = elapsed > 0 ? (recordsSinceLastLog * 1000.0 / elapsed) : 0;

            Duration totalElapsed = Duration.between(startTime, Instant.now());
            double overallTps = totalElapsed.toMillis() > 0 ? (total * 1000.0 / totalElapsed.toMillis()) : 0;

            LOG.infof("[%s]TPS Progress: %d processed | Current: %.0f msg/s | Overall: %.0f msg/s | Elapsed: %s",
                    METHOD_CONSUME, total, tps, overallTps, formatDuration(totalElapsed));

            lastProgressTime.set(currentTime);
            lastProgressCount.set(total);
        }
    }

    private static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        return h > 0
                ? String.format("%dh %02dm %02ds", h, m, s)
                : String.format("%dm %02ds", m, s);
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

