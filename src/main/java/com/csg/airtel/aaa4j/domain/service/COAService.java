package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
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

}
