package com.csg.airtel.aaa4j.domain.produce;

import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.QuotaNotificationEvent;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.service.FailoverPathLogger;
import com.csg.airtel.aaa4j.domain.service.SessionLifecycleManager;
import com.csg.airtel.aaa4j.domain.util.StructuredLogger;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.micrometer.core.instrument.Timer;
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

import java.util.concurrent.CompletableFuture;


@ApplicationScoped
public class AccountProducer {

    private static final StructuredLogger LOG = StructuredLogger.getLogger(AccountProducer.class);
    private final Emitter<DBWriteRequest> dbWriteRequestEmitter;
    private final Emitter<AccountingResponseEvent> accountingResponseEmitter;
    private final Emitter<AccountingCDREvent> accountingCDREventEmitter;
    private final Emitter<QuotaNotificationEvent> quotaNotificationEmitter;
    private final CacheClient cacheClient;
    private final SessionLifecycleManager sessionLifecycleManager;
    private final com.csg.airtel.aaa4j.domain.service.MonitoringService monitoringService;

    public AccountProducer(@Channel("db-write-events")Emitter<DBWriteRequest> dbWriteRequestEmitter,
                           @Channel("accounting-resp-events")Emitter<AccountingResponseEvent> accountingResponseEmitter,
                           @Channel("accounting-cdr-events") Emitter<AccountingCDREvent> accountingCDREventEmitter,
                           @Channel("quota-notification-events") Emitter<QuotaNotificationEvent> quotaNotificationEmitter,
                           CacheClient cacheClient,
                           SessionLifecycleManager sessionLifecycleManager,
                           com.csg.airtel.aaa4j.domain.service.MonitoringService monitoringService
                          ) {
        this.dbWriteRequestEmitter = dbWriteRequestEmitter;
        this.accountingResponseEmitter = accountingResponseEmitter;
        this.accountingCDREventEmitter = accountingCDREventEmitter;
        this.quotaNotificationEmitter = quotaNotificationEmitter;
        this.cacheClient = cacheClient;
        this.sessionLifecycleManager = sessionLifecycleManager;
        this.monitoringService = monitoringService;
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
        // High-TPS optimized timer recording for Kafka operations
        Timer.Sample timerSample = Timer.start();
        long startTime = System.currentTimeMillis();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Producing DB write event to Kafka", StructuredLogger.Fields.create()
                    .add("sessionId", request.getSessionId())
                    .add("userName", request.getUserName())
                    .add("eventType", request.getEventType() != null ? request.getEventType().toString() : "null")
                    .addComponent("kafka-producer")
                    .build());
        }

        return Uni.createFrom().emitter(em -> {
            Message<DBWriteRequest> message = Message.of(request)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(request.getSessionId())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        long duration = System.currentTimeMillis() - startTime;
                        timerSample.stop(monitoringService.getKafkaPublishTimer());
                        LOG.info("Successfully sent DB write event to Kafka", StructuredLogger.Fields.create()
                                .add("sessionId", request.getSessionId())
                                .add("userName", request.getUserName())
                                .addDuration(duration)
                                .addStatus("success")
                                .addComponent("kafka-producer")
                                .add("topic", "DC-DR")
                                .build());
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        long duration = System.currentTimeMillis() - startTime;
                        timerSample.stop(monitoringService.getKafkaPublishTimer());
                        monitoringService.recordKafkaProduceFailure();
                        LOG.error("Failed to send DB write event to Kafka", StructuredLogger.Fields.create()
                                .add("sessionId", request.getSessionId())
                                .add("userName", request.getUserName())
                                .addDuration(duration)
                                .addStatus("failed")
                                .addErrorCode("KAFKA_SEND_FAILED")
                                .addComponent("kafka-producer")
                                .add("topic", "DC-DR")
                                .add("error", throwable.getMessage())
                                .build());
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

        if (LOG.isDebugEnabled()) {
            LOG.debug("Producing accounting response event to Kafka", StructuredLogger.Fields.create()
                    .add("sessionId", event.sessionId())
                    .add("eventType", event.eventType() != null ? event.eventType().toString() : "null")
                    .addComponent("kafka-producer")
                    .build());
        }

        return Uni.createFrom().emitter(em -> {
            Message<AccountingResponseEvent> message = Message.of(event)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(event.sessionId())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        long duration = System.currentTimeMillis() - startTime;
                        LOG.info("Successfully sent accounting response event to Kafka", StructuredLogger.Fields.create()
                                .add("sessionId", event.sessionId())
                                .add("eventType", event.eventType() != null ? event.eventType().toString() : "null")
                                .addDuration(duration)
                                .addStatus("success")
                                .addComponent("kafka-producer")
                                .add("topic", "accounting-response")
                                .build());
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        long duration = System.currentTimeMillis() - startTime;
                        LOG.error("Failed to send accounting response event to Kafka", StructuredLogger.Fields.create()
                                .add("sessionId", event.sessionId())
                                .addDuration(duration)
                                .addStatus("failed")
                                .addErrorCode("KAFKA_SEND_FAILED")
                                .addComponent("kafka-producer")
                                .add("topic", "accounting-response")
                                .add("error", throwable.getMessage())
                                .build());
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
     *
     * @param request the DB write request
     * @param throwable the failure cause
     * @return Uni that completes when session is revoked
     */
    private Uni<Void> fallbackProduceDBWriteEvent(DBWriteRequest request, Throwable throwable) {
        FailoverPathLogger.logFallbackPath(LOG, "produceDBWriteEvent", request.getSessionId(), throwable);

        String userId = request.getUserName();
        String sessionId = request.getSessionId();


        LOG.infof("Initiating session revoke fallback for userId=%s, sessionId=%s", userId, sessionId);

        return cacheClient.getUserData(userId)
                .onItem().transformToUni(userSessionData -> {
                    if (userSessionData == null) {
                        LOG.debugf("No user session data found for userId=%s during fallback revoke", userId);
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
                        LOG.infof("Removed session %s from user %s sessions list during fallback", sessionId, userId);

                        // Update cache and remove from expiry index
                        return cacheClient.updateUserAndRelatedCaches(userId, userSessionData)
                                .call(() -> sessionLifecycleManager.onSessionTerminated(userId, sessionId))
                                .invoke(() -> LOG.infof("Session revoke fallback completed for userId=%s, sessionId=%s",
                                        userId, sessionId))
                                .onFailure().invoke(e -> LOG.errorf(e.getMessage(),
                                        "Failed to complete session revoke fallback for userId=%s, sessionId=%s",
                                        userId, sessionId))
                                .onFailure().recoverWithNull()
                                .replaceWithVoid();
                    } else {
                        LOG.debugf("Session %s not found in user %s sessions during fallback revoke", sessionId, userId);
                        // Still try to clean up expiry index in case it exists there
                        return sessionLifecycleManager.onSessionTerminated(userId, sessionId);
                    }
                })
                .onFailure().invoke(e -> LOG.errorf(e.getMessage(),
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
        LOG.infof("Start produce Quota Notification Event for user: %s, threshold: %s",
                event.username(), event.type());
        return Uni.createFrom().emitter(em -> {
            Message<QuotaNotificationEvent> message = Message.of(event)
                    .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                            .withKey(event.username())
                            .build())
                    .withAck(() -> {
                        em.complete(null);
                        LOG.infof("Successfully sent quota notification event for user: %s, %d ms",
                                event.username(), System.currentTimeMillis() - startTime);
                        return CompletableFuture.completedFuture(null);
                    })
                    .withNack(throwable -> {
                        LOG.errorf("Quota notification send failed: %s", throwable.getMessage());
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
