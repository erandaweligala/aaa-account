package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.repository.UserBucketRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class InterimHandler extends AbstractAccountingHandler {
    private static final Logger log = Logger.getLogger(InterimHandler.class);
    private static final String DATA_QUOTA_ZERO_MSG = "Data quota is zero";

    private final AccountingUtil accountingUtil;
    private final SessionLifecycleManager sessionLifecycleManager;

    protected InterimHandler() {
        super();
        this.accountingUtil = null;
        this.sessionLifecycleManager = null;
    }

    @Inject
    public InterimHandler(CacheClient cacheUtil, UserBucketRepository userRepository, AccountingUtil accountingUtil, AccountProducer accountProducer, SessionLifecycleManager sessionLifecycleManager, COAService coaService) {
        super(cacheUtil, userRepository, accountProducer, coaService);
        this.accountingUtil = accountingUtil;
        this.sessionLifecycleManager = sessionLifecycleManager;
    }

    public Uni<Void> handleInterim(AccountingRequestDto request,String traceId) {
        long startTime = System.currentTimeMillis();
        log.infof("[traceId: %s] Processing interim accounting request Start for user: %s, sessionId: %s",traceId,
                request.username(), request.sessionId());
        return cacheUtil.getUserData(request.username())
                .onItem().invoke(() -> {
                    if (log.isDebugEnabled()) {
                        log.debugf("User data retrieved for user: %s", request.username());
                    }
                })
                .onItem().transformToUni(userSessionData ->
                        userSessionData == null
                                ? handleNewSessionUsage(request,traceId).invoke(() -> log.infof("[traceId: %s] Completed processing interim accounting for new session for  %s ms",traceId,System.currentTimeMillis()-startTime))
                                : processAccountingRequest(userSessionData, request,traceId).invoke(() -> log.infof("[traceId: %s] Completed processing interim accounting for existing session for  %s ms",traceId, System.currentTimeMillis()-startTime))

                )
                .onFailure().recoverWithUni(throwable -> {
                    // Handle circuit breaker open specifically - cache service temporarily unavailable
                    if (throwable instanceof CircuitBreakerOpenException) {
                        log.errorf("traceId: %s Cache service circuit breaker is OPEN for user: %s. " +
                                        "Service temporarily unavailable due to high tps or Redis connectivity issues.",
                                traceId, request.username());
                        return Uni.createFrom().voidItem();
                    }
                    // Handle other errors
                    log.errorf(throwable, "[traceId: %s] Error processing accounting for user: %s",
                            traceId, request.username());
                    return Uni.createFrom().voidItem();
                });
    }



    @Override
    protected Uni<Void> processAccountingRequest(
            UserSessionData userData, AccountingRequestDto request,String traceId) {
        long startTime = System.currentTimeMillis();
        log.infof("TraceId: %s Processing interim accounting request for user: %s, sessionId: %s",
                traceId,request.username(), request.sessionId());
        Session session = findSessionById(userData.getSessions(), request.sessionId());
        int i = 0;
        if (session == null) {
            session = createSession(request);
            session.setGroupId(userData.getGroupId());
            session.setAbsoluteTimeOut(userData.getSessionTimeOut());
            i = 1;
        }
        
        boolean hasMatchingNasPortId = userData.getSessions().stream()
                .anyMatch(ses -> ses.getNasPortId() != null &&
                        ses.getNasPortId().equals(request.nasPortId()));

        if(!hasMatchingNasPortId && userData.getConcurrency() < userData.getSessions().size()+i) {
            log.errorf("Maximum number of concurrency sessions exceeded for user: %s", request.username());
            return coaService.produceAccountingResponseEvent(MappingUtil.createResponse(request, DATA_QUOTA_ZERO_MSG, AccountingResponseEvent.EventType.COA,
                    AccountingResponseEvent.ResponseAction.DISCONNECT),createSession(request),request.username());

        }

        if("BARRED".equalsIgnoreCase(userData.getUserStatus())){
            log.warnf("User status is BARRED for user: %s", request.username());
            generateAndSendCDR(request, session, session.getServiceId(), session.getPreviousUsageBucketId());
            return Uni.createFrom().voidItem();
        }
        // Early return if session time hasn't increased
        if (request.sessionTime() <= session.getSessionTime()) {
            log.warnf("TraceId: %s Duplicate Session time unchanged for sessionId: %s", traceId,request.sessionId());
            return Uni.createFrom().voidItem();

        } else {
            Session finalSession = session;
            return accountingUtil.updateSessionAndBalance(userData, session, request,null)
                    .call(() -> sessionLifecycleManager.onSessionActivity(request.username(), request.sessionId()))
                    .onItem().transformToUni(updateResult -> {
                        if (!updateResult.success()) {
                            log.warnf("update failed for sessionId: %s", request.sessionId());
                        }
                        log.infof("Interim accounting processing time ms : %d",
                                System.currentTimeMillis() - startTime);
                        generateAndSendCDR(request, finalSession, finalSession.getServiceId(), finalSession.getPreviousUsageBucketId());
                        return Uni.createFrom().voidItem();

                    });
        }
    }

    @Override
    protected Session createSession(AccountingRequestDto request) {
        return new Session(
                request.sessionId(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                request.sessionTime() - 1,
                0L,
                request.framedIPAddress(),
                request.nasIP(),
                request.nasPortId(),
                true,
                0,null,
                request.username(),null,null

        );
    }


    private void generateAndSendCDR(AccountingRequestDto request, Session session, String serviceId, String bucketId) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildInterimCDREvent, serviceId, bucketId);
    }



}