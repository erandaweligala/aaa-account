package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

@ApplicationScoped
public class InterimHandler {
    private static final Logger log = Logger.getLogger(InterimHandler.class);

    private final AbstractAccountingHandler accountingHandler;
    private final CacheClient cacheUtil;
    private final AccountProducer accountProducer;
    private final COAService coaService;
    private final AccountingUtil accountingUtil;
    private final SessionLifecycleManager sessionLifecycleManager;

    @Inject
    public InterimHandler(
            AbstractAccountingHandler accountingHandler,
            CacheClient cacheUtil,
            AccountProducer accountProducer,
            COAService coaService,
            AccountingUtil accountingUtil,
            SessionLifecycleManager sessionLifecycleManager) {
        this.accountingHandler = accountingHandler;
        this.cacheUtil = cacheUtil;
        this.accountProducer = accountProducer;
        this.coaService = coaService;
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
                                ? accountingHandler.handleNewSessionUsage(request, traceId, this::processAccountingRequest, this::createSession)
                                        .invoke(() -> log.infof("[traceId: %s] Completed processing interim accounting for new session for  %s ms",traceId,System.currentTimeMillis()-startTime))
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

    private Uni<Void> processAccountingRequest(
            UserSessionData userData, AccountingRequestDto request, String cachedGroupData) {
        long startTime = System.currentTimeMillis();
        log.infof("Processing interim accounting request for user: %s, sessionId: %s",
                request.username(), request.sessionId());
        Session session = accountingHandler.findSessionById(userData.getSessions(), request.sessionId());

        if (session == null) {
            long concurrency = userData.getConcurrency();
            String userStatus = userData.getUserStatus();
            String sessionTimeout = userData.getSessionTimeOut();

            if(cachedGroupData != null) {
                int p1 = cachedGroupData.indexOf(',');
                int p2 = cachedGroupData.indexOf(',', p1 + 1);
                int p3 = cachedGroupData.indexOf(',', p2 + 1);

                concurrency = parseLongFast(cachedGroupData, p1 + 1, p2);
                userStatus = cachedGroupData.substring(p2 + 1, p3);
                sessionTimeout = cachedGroupData.substring(p3 + 1);
            }

            session = createSession(request);
            session.setGroupId(userData.getGroupId());
            session.setAbsoluteTimeOut(sessionTimeout);
            session.setUserConcurrency(concurrency);
            session.setUserStatus(userStatus);
        }

        if("BARRED".equalsIgnoreCase(userData.getUserStatus())){
            log.warnf("User status is BARRED for user: %s", request.username());
            generateAndSendCDR(request, session, session.getServiceId(), session.getPreviousUsageBucketId());
            return Uni.createFrom().voidItem();
        }
        // Early return if session time hasn't increased
        if (request.sessionTime() <= session.getSessionTime()) {
            log.warnf("Duplicate Session time unchanged for sessionId: %s", request.sessionId());
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

    static long parseLongFast(String s, int start, int end) {
        long val = 0;
        for (int i = start; i < end; i++) {
            val = val * 10 + (s.charAt(i) - '0');
        }
        return val;
    }


    private Session createSession(AccountingRequestDto request) {
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
                request.username(),null,null,
                null,
                0

        );
    }


    private void generateAndSendCDR(AccountingRequestDto request, Session session, String serviceId, String bucketId) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildInterimCDREvent, serviceId, bucketId);
    }



}