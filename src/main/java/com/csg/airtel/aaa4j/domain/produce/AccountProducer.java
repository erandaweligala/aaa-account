package com.csg.airtel.aaa4j.domain.produce;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.QuotaNotificationEvent;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.service.FailoverPathLogger;
import com.csg.airtel.aaa4j.domain.service.SessionLifecycleManager;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
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
    private static final String CLASS_NAME = AccountProducer.class.getSimpleName();
    private final Emitter<DBWriteRequest> dbWriteRequestEmitter;
    private final Emitter<AccountingResponseEvent> accountingResponseEmitter;
    private final Emitter<AccountingCDREvent> accountingCDREventEmitter;
    private final Emitter<QuotaNotificationEvent> quotaNotificationEmitter;
    private final CacheClient cacheClient;
    private final SessionLifecycleManager sessionLifecycleManager;

    public AccountProducer(@Channel("db-write-events")Emitter<DBWriteRequest> dbWriteRequestEmitter,
                           @Channel("accounting-resp-events")Emitter<AccountingResponseEvent> accountingResponseEmitter,
                           @Channel("accounting-cdr-events") Emitter<AccountingCDREvent> accountingCDREventEmitter,
                           @Channel("quota-notification-events") Emitter<QuotaNotificationEvent> quotaNotificationEmitter,
                           CacheClient cacheClient,
                           SessionLifecycleManager sessionLifecycleManager
                          ) {
        this.dbWriteRequestEmitter = dbWriteRequestEmitter;
        this.accountingResponseEmitter = accountingResponseEmitter;
        this.accountingCDREventEmitter = accountingCDREventEmitter;
        this.quotaNotificationEmitter = quotaNotificationEmitter;
        this.cacheClient = cacheClient;
        this.sessionLifecycleManager = sessionLifecycleManager;
    }

    @CircuitBreaker(
            requestVolumeThreshold = 200,
            failureRatio = 0.75,
            delay = 3000,
            successThreshold = 3
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
        LoggingUtil.logInfo(LOG, CLASS_NAME, "produceDBWriteEvent", "Start produceDBWriteEvent process Started sessionId : %s", request.getSessionId());
        return Uni.createFrom().emitter(em -> {
            Message<DBWriteRequest> message = Message.of(request)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(request.getSessionId())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        LoggingUtil.logInfo(LOG, CLASS_NAME, "produceDBWriteEvent", "Successfully sent accounting DB create event for session: %s, %d ms", request.getSessionId(),System.currentTimeMillis()-startTime);
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        LoggingUtil.logError(LOG, CLASS_NAME, "produceDBWriteEvent", null, "Send failed: %s", throwable.getMessage());
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
            requestVolumeThreshold = 200,
            failureRatio = 0.75,
            delay = 3000,
            successThreshold = 3
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
        LoggingUtil.logInfo(LOG, CLASS_NAME, "produceAccountingResponseEvent", "Start produceAccountingResponseEvent process");
        return Uni.createFrom().emitter(em -> {
            Message<AccountingResponseEvent> message = Message.of(event)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(event.sessionId())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        LoggingUtil.logInfo(LOG, CLASS_NAME, "produceAccountingResponseEvent", "Successfully sent accounting response event for session: %s, %d ms", event.sessionId(),System.currentTimeMillis()-startTime);
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        LoggingUtil.logError(LOG, CLASS_NAME, "produceAccountingResponseEvent", null, "Send failed: %s", throwable.getMessage());
                        em.fail(throwable);
                        return CompletableFuture.completedFuture(null);
                    });

            accountingResponseEmitter.send(message);
        });
    }

    @CircuitBreaker(
            requestVolumeThreshold = 200,
            failureRatio = 0.75,
            delay = 3000,
            successThreshold = 3
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
        LoggingUtil.logInfo(LOG, CLASS_NAME, "produceAccountingCDREvent", "Start produce Accounting CDR Event process");
        return Uni.createFrom().emitter(em -> {
            Message<AccountingCDREvent> message = Message.of(event)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(event.getPayload().getSession().getSessionId())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        LoggingUtil.logInfo(LOG, CLASS_NAME, "produceAccountingCDREvent", "Successfully sent accounting CDR event for session: %s, %d ms", event.getPayload().getSession().getSessionId(),System.currentTimeMillis()-startTime);
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        LoggingUtil.logError(LOG, CLASS_NAME, "produceAccountingCDREvent", null, "CDR Send failed: %s", throwable.getMessage());
                        em.fail(throwable);
                        return CompletableFuture.completedFuture(null);
                    });

            accountingCDREventEmitter.send(message);
        });

    }

    /**
     *
     * @param request the DB write request
     * @param throwable the failure cause
     * @return Uni that completes when session is revoked
     */
    private Uni<Void> fallbackProduceDBWriteEvent(DBWriteRequest request, Throwable throwable) {
        FailoverPathLogger.logFallbackPath(LOG, "produceDBWriteEvent", request.getSessionId(), throwable);

        String userId = request.getUserName();
        String sessionId = request.getSessionId();


        LoggingUtil.logInfo(LOG, CLASS_NAME, "fallbackProduceDBWriteEvent", "Initiating session revoke fallback for userId=%s, sessionId=%s", userId, sessionId);

        return cacheClient.getUserData(userId)
                .onItem().transformToUni(userSessionData -> {
                    if (userSessionData == null) {
                        LoggingUtil.logDebug(LOG, CLASS_NAME, "fallbackProduceDBWriteEvent", "No user session data found for userId=%s during fallback revoke", userId);
                        return Uni.createFrom().voidItem();
                    }

                    // Find and remove the session from user's sessions list
                    Session sessionToRemove = null;
                    if (userSessionData.getSessions() != null) {
                        for (Session session : userSessionData.getSessions()) {
                            if (sessionId.equals(session.getSessionId())) {
                                sessionToRemove = session;
                                break;
                            }
                        }
                    }

                    if (sessionToRemove != null) {
                        userSessionData.getSessions().remove(sessionToRemove);
                        LoggingUtil.logInfo(LOG, CLASS_NAME, "fallbackProduceDBWriteEvent", "Removed session %s from user %s sessions list during fallback", sessionId, userId);

                        // Update cache and remove from expiry index
                        return cacheClient.updateUserAndRelatedCaches(userId, userSessionData,request.getUserName())
                                .call(() -> sessionLifecycleManager.onSessionTerminated(userId, sessionId))
                                .invoke(() -> LoggingUtil.logInfo(LOG, CLASS_NAME, "fallbackProduceDBWriteEvent", "Session revoke fallback completed for userId=%s, sessionId=%s",
                                        userId, sessionId))
                                .onFailure().invoke(e -> LoggingUtil.logError(LOG, CLASS_NAME, "fallbackProduceDBWriteEvent", e,
                                        "Failed to complete session revoke fallback for userId=%s, sessionId=%s",
                                        userId, sessionId))
                                .onFailure().recoverWithNull()
                                .replaceWithVoid();
                    } else {
                        LoggingUtil.logDebug(LOG, CLASS_NAME, "fallbackProduceDBWriteEvent", "Session %s not found in user %s sessions during fallback revoke", sessionId, userId);
                        // Still try to clean up expiry index in case it exists there
                        return sessionLifecycleManager.onSessionTerminated(userId, sessionId);
                    }
                })
                .onFailure().invoke(e -> LoggingUtil.logError(LOG, CLASS_NAME, "fallbackProduceDBWriteEvent", e,
                        "Failed to retrieve user data during session revoke fallback for userId=%s, sessionId=%s",
                        userId, sessionId))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
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

    /**
     * Publish quota notification event to Kafka.
     *
     * @param event the quota notification event
     * @return Uni that completes when event is published
     */
    @CircuitBreaker(
            requestVolumeThreshold = 200,
            failureRatio = 0.75,
            delay = 3000,
            successThreshold = 3
    )
    @Retry(
            maxRetries = 3,
            delay = 100,
            maxDuration = 10000
    )
    @Timeout(value = 10000)
    @Fallback(fallbackMethod = "fallbackProduceQuotaNotificationEvent")
    public Uni<Void> produceQuotaNotificationEvent(QuotaNotificationEvent event) {
        long startTime = System.currentTimeMillis();
        LoggingUtil.logInfo(LOG, CLASS_NAME, "produceQuotaNotificationEvent", "Start produce Quota Notification Event for user: %s, threshold: %s",
                event.username(), event.type());
        return Uni.createFrom().emitter(em -> {
            Message<QuotaNotificationEvent> message = Message.of(event)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(event.username())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        LoggingUtil.logInfo(LOG, CLASS_NAME, "produceQuotaNotificationEvent", "Successfully sent quota notification event for user: %s, %d ms",
                                event.username(), System.currentTimeMillis() - startTime);
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        LoggingUtil.logError(LOG, CLASS_NAME, "produceQuotaNotificationEvent", null, "Quota notification send failed: %s", throwable.getMessage());
                        em.fail(throwable);
                        return CompletableFuture.completedFuture(null);
                    });

            quotaNotificationEmitter.send(message);
        });
    }

    /**
     * Fallback method for produceQuotaNotificationEvent.
     *
     * @param event the quota notification event
     * @param throwable the failure cause
     * @return empty Uni (operation failed)
     */
    private Uni<Void> fallbackProduceQuotaNotificationEvent(QuotaNotificationEvent event, Throwable throwable) {
        FailoverPathLogger.logFallbackPath(LOG, "produceQuotaNotificationEvent", event.username(), throwable);
        return Uni.createFrom().voidItem();
    }

}
