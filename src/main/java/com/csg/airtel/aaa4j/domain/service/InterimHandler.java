package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.domain.util.StructuredLogger;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import com.csg.airtel.aaa4j.external.repository.UserBucketRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class InterimHandler {
    private static final StructuredLogger log = StructuredLogger.getLogger(InterimHandler.class);
    private static final String NO_SERVICE_BUCKETS_MSG = "No service buckets found";
    private static final String DATA_QUOTA_ZERO_MSG = "Data quota is zero";


    private final CacheClient cacheUtil;
    private final UserBucketRepository userRepository;
    private final AccountingUtil accountingUtil;
    private final AccountProducer accountProducer;
    private final SessionLifecycleManager sessionLifecycleManager;
    private final COAService coaService;

    @Inject
    public InterimHandler(CacheClient cacheUtil, UserBucketRepository userRepository, AccountingUtil accountingUtil, AccountProducer accountProducer, SessionLifecycleManager sessionLifecycleManager, COAService coaService) {
        this.cacheUtil = cacheUtil;
        this.userRepository = userRepository;
        this.accountingUtil = accountingUtil;
        this.accountProducer = accountProducer;
        this.sessionLifecycleManager = sessionLifecycleManager;
        this.coaService = coaService;
    }

    public Uni<Void> handleInterim(AccountingRequestDto request,String traceId) {
        long startTime = System.currentTimeMillis();

        // Set MDC context for correlation
        StructuredLogger.setContext(traceId, request.username(), request.sessionId());
        StructuredLogger.setOperation("INTERIM");

        log.info("Processing accounting INTERIM request", StructuredLogger.Fields.create()
                .add("username", request.username())
                .add("sessionId", request.sessionId())
                .add("acctInputOctets", request.inputOctets())
                .add("acctOutputOctets", request.outputOctets())
                .build());

        return cacheUtil.getUserData(request.username())
                .onItem().invoke(() -> {
                    if (log.isDebugEnabled()) {
                        log.debug("User data retrieved for INTERIM", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .build());
                    }
                })
                .onItem().transformToUni(userSessionData ->
                        userSessionData == null
                                ? handleNewSessionUsage(request,traceId).invoke(() -> {
                                    long duration = System.currentTimeMillis() - startTime;
                                    log.info("Completed INTERIM processing for new session", StructuredLogger.Fields.create()
                                            .add("username", request.username())
                                            .addDuration(duration)
                                            .addStatus("success")
                                            .build());
                                })
                                : processAccountingRequest(userSessionData, request,traceId).invoke(() -> {
                                    long duration = System.currentTimeMillis() - startTime;
                                    log.info("Completed INTERIM processing for existing session", StructuredLogger.Fields.create()
                                            .add("username", request.username())
                                            .addDuration(duration)
                                            .addStatus("success")
                                            .build());
                                })

                )
                .onFailure().recoverWithUni(throwable -> {
                    long duration = System.currentTimeMillis() - startTime;

                    // Handle circuit breaker open specifically
                    if (throwable instanceof CircuitBreakerOpenException) {
                        log.error("Cache service circuit breaker OPEN", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .addErrorCode("CIRCUIT_BREAKER_OPEN")
                                .addDuration(duration)
                                .addStatus("failed")
                                .add("reason", "Redis connectivity issues or high TPS")
                                .build());
                        return Uni.createFrom().voidItem();
                    }
                    // Handle other errors
                    log.error("Error processing INTERIM accounting", throwable, StructuredLogger.Fields.create()
                            .add("username", request.username())
                            .addErrorCode("INTERIM_PROCESSING_ERROR")
                            .addDuration(duration)
                            .addStatus("failed")
                            .add("errorType", throwable.getClass().getSimpleName())
                            .build());
                    return Uni.createFrom().voidItem();
                })
                .eventually(() -> StructuredLogger.clearContext());
    }

    private Uni<Void> handleNewSessionUsage(AccountingRequestDto request,String traceId) {

        if (log.isDebugEnabled()) {
            log.debugf("No cache entry found for user: %s", request.username());
        }
        return userRepository.getServiceBucketsByUserName(request.username())
                .onItem().transformToUni(serviceBuckets -> {
                    if (serviceBuckets == null || serviceBuckets.isEmpty()) {
                        log.warn("No service buckets found for user: %s", request.username());
                        return coaService.produceAccountingResponseEvent(MappingUtil.createResponse(request, NO_SERVICE_BUCKETS_MSG, AccountingResponseEvent.EventType.COA,
                                AccountingResponseEvent.ResponseAction.DISCONNECT),createSession(request),request.username());
                    }
                    int bucketCount = serviceBuckets.size();
                    List<Balance> balanceList = new ArrayList<>(bucketCount);
                    String groupId = null;
                    long concurrency = 0;
                    String templates = null;
                    for (ServiceBucketInfo bucket : serviceBuckets) {
                        if(!Objects.equals(bucket.getBucketUser(), request.username())){
                            groupId = bucket.getBucketUser();
                        }
                        concurrency = bucket.getConcurrency();
                        templates = bucket.getNotificationTemplates();
                        balanceList.add(MappingUtil.createBalance(bucket));

                    }
                    Session newSession = createSession(request);
                    newSession.setGroupId(groupId);
                    UserSessionData newUserSessionData =  UserSessionData.builder().templateIds(templates)
                            .groupId(groupId).userName(request.username()).concurrency(concurrency)
                            .balance(balanceList).sessions(new ArrayList<>(List.of(newSession))).build();
                    return processAccountingRequest(newUserSessionData, request,traceId);

                });
    }

    private Uni<Void> processAccountingRequest(
            UserSessionData userData, AccountingRequestDto request,String traceId) {
        long startTime = System.currentTimeMillis();
        log.infof("TraceId: %s Processing interim accounting request for user: %s, sessionId: %s",
                traceId,request.username(), request.sessionId());
        Session session = findSession(userData, request.sessionId());
        int i = 0;
        if (session == null) {
            session = createSession(request);
            session.setGroupId(userData.getGroupId());
            i = 1;
        }

        if(userData.getConcurrency() < userData.getSessions().size()+i) {
            log.errorf("Maximum number of concurrency sessions exceeded for user: %s", request.username());
            return coaService.produceAccountingResponseEvent(MappingUtil.createResponse(request, DATA_QUOTA_ZERO_MSG, AccountingResponseEvent.EventType.COA,
                    AccountingResponseEvent.ResponseAction.DISCONNECT),createSession(request),request.username());

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
                        generateAndSendCDR(request, finalSession);
                        return Uni.createFrom().voidItem();

                    });
        }
    }

    private Session findSession(UserSessionData userData, String sessionId) {
        List<Session> sessions = userData.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        for (Session session : sessions) {
            if (session.getSessionId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    private Session createSession(AccountingRequestDto request) {
        return new Session(
                request.sessionId(),
                LocalDateTime.now(),
                null,
                request.sessionTime() - 1,
                0L,
                request.framedIPAddress(),
                request.nasIP(),
                request.nasPortId(),
                true,
                0,null

        );
    }

    private void generateAndSendCDR(AccountingRequestDto request, Session session) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildInterimCDREvent);
    }


}