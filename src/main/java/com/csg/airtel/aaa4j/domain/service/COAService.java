package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;


@ApplicationScoped
public class COAService {
    private static final Logger log = Logger.getLogger(COAService.class);

    private final AccountProducer accountProducer;
    private final MonitoringService monitoringService;

    public COAService(AccountProducer accountProducer, MonitoringService monitoringService) {
        this.accountProducer = accountProducer;
        this.monitoringService = monitoringService;
    }

    public Uni<Void> clearAllSessionsAndSendCOA(UserSessionData userSessionData, String username) {
        List<Session> sessions = userSessionData.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        // Use merge instead of concatenate for parallel execution (better throughput)
        return Multi.createFrom().iterable(sessions)
                .onItem().transformToUni(session ->
                        accountProducer.produceAccountingResponseEvent(
                                        MappingUtil.createResponse(
                                                session.getSessionId(),
                                                AppConstant.DISCONNECT_ACTION,
                                                session.getNasIp(),
                                                session.getFramedId(),
                                                username
                                        )
                                )
                                .invoke(() -> {
                                    // Record COA request metric
                                    monitoringService.recordCOARequest();
                                    generateAndSendCoaDisconnectCDR(session, username);
                                })
                                .onFailure().retry()
                                .withBackOff(Duration.ofMillis(AppConstant.COA_RETRY_INITIAL_BACKOFF_MS), Duration.ofSeconds(AppConstant.COA_RETRY_MAX_BACKOFF_SECONDS))
                                .atMost(AppConstant.COA_RETRY_MAX_ATTEMPTS)
                                .onFailure().invoke(failure -> {
                                    if (log.isDebugEnabled()) {
                                        log.debugf(failure, "Failed to produce disconnect event for session: %s",
                                                session.getSessionId());
                                    }
                                })
                                .onFailure().recoverWithNull()
                )
                .merge() // Parallel execution instead of sequential
                .collect().asList()
                .ifNoItem().after(Duration.ofSeconds(AppConstant.COA_TIMEOUT_SECONDS)).fail()
                .replaceWithVoid();
    }

    /**
     * Generate and send COA Disconnect CDR event asynchronously.
     * This method builds a CDR event for a COA disconnect operation and sends it to the accounting system.
     *
     * @param session the session being disconnected
     * @param username the username associated with the session
     */
    private void generateAndSendCoaDisconnectCDR(Session session, String username) {
        try {
            AccountingCDREvent cdrEvent = CdrMappingUtil.buildCoaDisconnectCDREvent(session, username);
            accountProducer.produceAccountingCDREvent(cdrEvent)
                    .subscribe()
                    .with(
                            success -> log.infof("COA Disconnect CDR event sent successfully for session: %s, user: %s",
                                    session.getSessionId(), username),
                            failure -> log.errorf(failure, "Failed to send COA Disconnect CDR event for session: %s, user: %s",
                                    session.getSessionId(), username)
                    );
        } catch (Exception e) {
            log.errorf(e, "Error building COA Disconnect CDR event for session: %s, user: %s",
                    session.getSessionId(), username);
        }
    }

    /**
     * Produce accounting response event and generate COA disconnect CDR.
     * This method sends an accounting response event and then generates a COA disconnect CDR for the session.
     *
     * @param event the accounting response event to send
     * @param session the session being disconnected
     * @param username the username associated with the session
     * @return Uni that completes when the event is sent
     */
    public Uni<Void> produceAccountingResponseEvent(AccountingResponseEvent event, Session session, String username) {
        return accountProducer.produceAccountingResponseEvent(event)
                .invoke(() -> {
                    // Record COA request metric
                    monitoringService.recordCOARequest();
                    generateAndSendCoaDisconnectCDR(session, username);
                });
    }

}
