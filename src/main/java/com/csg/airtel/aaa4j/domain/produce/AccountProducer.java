package com.csg.airtel.aaa4j.domain.produce;

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
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;


@ApplicationScoped
public class AccountProducer {

    private static final Logger LOG = Logger.getLogger(AccountProducer.class);
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

    @Counted(name = "account.producer.db.write.events", description = "Total DB write events produced")
    @Timed(name = "account.producer.db.write.time", description = "Time to produce DB write events")
    @CircuitBreaker(
            requestVolumeThreshold = 200,
            failureRatio = 0.75,
            delay = 3000,
            successThreshold = 3
    )
    @Retry(
            maxRetries = 3,
            delay = 100,
            maxDuration = 50000
    )
    @Timeout(value = 60000)
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
                        LOG.errorf(throwable, "DB write event send failed for sessionId=%s, userName=%s: %s",
                                request.getSessionId(), request.getUserName(), throwable.getMessage());
                        em.fail(throwable);
                        return CompletableFuture.completedFuture(null);
                    });

            dbWriteRequestEmitter.send(message);
        });
    }

    /**
     * @param event request
     */
    @Counted(name = "account.producer.accounting.response.events", description = "Total accounting response events produced")
    @Timed(name = "account.producer.accounting.response.time", description = "Time to produce accounting response events")
    @CircuitBreaker(
            requestVolumeThreshold = 200,
            failureRatio = 0.75,
            delay = 3000,
            successThreshold = 3
    )
    @Retry(
            maxRetries = 3,
            delay = 100,
            maxDuration = 50000
    )
    @Timeout(value = 60000)
    @Fallback(fallbackMethod = "fallbackProduceAccountingResponseEvent")
    public Uni<Void> produceAccountingResponseEvent(AccountingResponseEvent event) {
        long startTime = System.currentTimeMillis();
        LOG.infof("Start produceAccountingResponseEvent process for sessionId=%s", event.sessionId());
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
                        LOG.errorf(throwable, "Accounting response event send failed for sessionId=%s: %s",
                                event.sessionId(), throwable.getMessage());
                        em.fail(throwable);
                        return CompletableFuture.completedFuture(null);
                    });

            accountingResponseEmitter.send(message);
        });
    }

    @Counted(name = "account.producer.accounting.cdr.events", description = "Total accounting CDR events produced")
    @Timed(name = "account.producer.accounting.cdr.time", description = "Time to produce accounting CDR events")
    @CircuitBreaker(
            requestVolumeThreshold = 200,
            failureRatio = 0.75,
            delay = 3000,
            successThreshold = 3
    )
    @Retry(
            maxRetries = 3,
            delay = 100,
            maxDuration = 50000
    )
    @Timeout(value = 60000)
    @Fallback(fallbackMethod = "fallbackProduceAccountingCDREvent")
    public Uni<Void> produceAccountingCDREvent(AccountingCDREvent event) {
        long startTime = System.currentTimeMillis();
        String sessionId = event.getPayload() != null && event.getPayload().getSession() != null
                ? event.getPayload().getSession().getSessionId()
                : "unknown";
        LOG.infof("Start produce Accounting CDR Event process for sessionId=%s", sessionId);
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
                        String sessionId = event.getPayload() != null && event.getPayload().getSession() != null
                                ? event.getPayload().getSession().getSessionId()
                                : "unknown";
                        LOG.errorf(throwable, "Accounting CDR event send failed for sessionId=%s: %s",
                                sessionId, throwable.getMessage());
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
                                .onFailure().invoke(e -> LOG.errorf(e,
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
                .onFailure().invoke(e -> LOG.errorf(e,
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
    @Counted(name = "account.producer.quota.notification.events", description = "Total quota notification events produced")
    @Timed(name = "account.producer.quota.notification.time", description = "Time to produce quota notification events")
    @CircuitBreaker(
            requestVolumeThreshold = 200,
            failureRatio = 0.75,
            delay = 3000,
            successThreshold = 3
    )
    @Retry(
            maxRetries = 3,
            delay = 100,
            maxDuration = 50000
    )
    @Timeout(value = 60000)
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
                        LOG.errorf(throwable, "Quota notification event send failed for user=%s, type=%s: %s",
                                event.username(), event.type(), throwable.getMessage());
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
