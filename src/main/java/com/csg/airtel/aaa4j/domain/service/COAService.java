package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
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

    public COAService(AccountProducer accountProducer) {
        this.accountProducer = accountProducer;
    }

    public Uni<Void> clearAllSessionsAndSendCOA(UserSessionData userSessionData, String username) {
        List<Session> sessions = userSessionData.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        log.infof("Starting COA Disconnect with CDR generation for %d sessions, user: %s",
                sessions.size(), username);

        // Use merge instead of concatenate for parallel execution (better throughput)
        return Multi.createFrom().iterable(sessions)
                .onItem().transformToUni(session ->
                        // First, generate and send CDR for COA Disconnect
                        generateCOADisconnectCDR(session, username)
                                .onFailure().invoke(failure ->
                                        log.warnf(failure, "Failed to generate CDR for COA disconnect, session: %s",
                                                session.getSessionId()))
                                .onFailure().recoverWithNull()
                                // Then send COA Disconnect event to NAS
                                .onItemOrFailure().transformToUni((item, failure) ->
                                        accountProducer.produceAccountingResponseEvent(
                                                        MappingUtil.createResponse(
                                                                session.getSessionId(),
                                                                AppConstant.DISCONNECT_ACTION,
                                                                session.getNasIp(),
                                                                session.getFramedId(),
                                                                username
                                                        )
                                                )
                                                .onFailure().retry()
                                                .withBackOff(Duration.ofMillis(AppConstant.COA_RETRY_INITIAL_BACKOFF_MS),
                                                        Duration.ofSeconds(AppConstant.COA_RETRY_MAX_BACKOFF_SECONDS))
                                                .atMost(AppConstant.COA_RETRY_MAX_ATTEMPTS)
                                                .onFailure().invoke(coaFailure -> {
                                                    if (log.isDebugEnabled()) {
                                                        log.debugf(coaFailure, "Failed to produce disconnect event for session: %s",
                                                                session.getSessionId());
                                                    }
                                                })
                                                .onFailure().recoverWithNull()
                                )
                )
                .merge() // Parallel execution instead of sequential
                .collect().asList()
                .ifNoItem().after(Duration.ofSeconds(AppConstant.COA_TIMEOUT_SECONDS)).fail()
                .replaceWithVoid();
    }

    /**
     * Generate and publish CDR event for COA Disconnect scenario
     */
    private Uni<Void> generateCOADisconnectCDR(Session session, String username) {
        try {
            AccountingCDREvent cdrEvent = CdrMappingUtil.buildCOADisconnectCDREvent(session, username);

            return accountProducer.produceAccountingCDREvent(cdrEvent)
                    .onItem().invoke(() ->
                            log.infof("COA Disconnect CDR sent successfully for session: %s", session.getSessionId()))
                    .onFailure().invoke(failure ->
                            log.errorf(failure, "Failed to send COA Disconnect CDR for session: %s", session.getSessionId()));
        } catch (Exception e) {
            log.errorf(e, "Error building COA Disconnect CDR for session: %s", session.getSessionId());
            return Uni.createFrom().failure(e);
        }
    }

}
