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
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class InterimHandler {
    private static final Logger log = Logger.getLogger(InterimHandler.class);
    private static final String NO_SERVICE_BUCKETS_MSG = "No service buckets found";
    private static final String DATA_QUOTA_ZERO_MSG = "Data quota is zero";


    private final CacheClient cacheUtil;
    private final UserBucketRepository userRepository;
    private final AccountingUtil accountingUtil;
    private final AccountProducer accountProducer;
    @Inject
    public InterimHandler(CacheClient cacheUtil, UserBucketRepository userRepository, AccountingUtil accountingUtil, AccountProducer accountProducer) {
        this.cacheUtil = cacheUtil;
        this.userRepository = userRepository;
        this.accountingUtil = accountingUtil;
        this.accountProducer = accountProducer;
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
                    log.errorf(throwable, "Error processing accounting for user: %s", request.username());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> handleNewSessionUsage(AccountingRequestDto request,String traceId) {

        if (log.isDebugEnabled()) {
            log.debugf("No cache entry found for user: %s", request.username());
        }
        return userRepository.getServiceBucketsByUserName(request.username())
                .onItem().transformToUni(serviceBuckets -> {
                    if (serviceBuckets == null || serviceBuckets.isEmpty()) {
                        log.warnf("No service buckets found for user: %s", request.username());
                       return accountProducer.produceAccountingResponseEvent(MappingUtil.createResponse(request, NO_SERVICE_BUCKETS_MSG, AccountingResponseEvent.EventType.COA,
                                AccountingResponseEvent.ResponseAction.DISCONNECT));
                    }
                    int bucketCount = serviceBuckets.size();
                    List<Balance> balanceList = new ArrayList<>(bucketCount);
                    double totalQuota = 0.0;
                    boolean hasUnlimitedBucket = false;
                    String groupId = null;

                    for (ServiceBucketInfo bucket : serviceBuckets) {
                        if(!Objects.equals(bucket.getBucketUser(), request.username())){
                            groupId = bucket.getBucketUser();
                        }
                        double currentBalance = bucket.getCurrentBalance();
                        double initialBalance = bucket.getInitialBalance();

                        // Check if this is an unlimited bucket (initialBalance = 0 and currentBalance/quota = 0)
                        if (initialBalance == 0 && currentBalance == 0) {
                            hasUnlimitedBucket = true;
                        }

                        totalQuota += currentBalance;
                        balanceList.add(MappingUtil.createBalance(bucket));

                    }

                    // Only disconnect if totalQuota is zero AND there are no unlimited buckets
                    if (totalQuota <= 0 && !hasUnlimitedBucket) {
                        log.warnf("User: %s has zero total data quota", request.username());
                        return accountProducer.produceAccountingResponseEvent(MappingUtil.createResponse(request, DATA_QUOTA_ZERO_MSG, AccountingResponseEvent.EventType.COA,
                                AccountingResponseEvent.ResponseAction.DISCONNECT));
                    }

                     UserSessionData newUserSessionData =  UserSessionData.builder()
                             .groupId(groupId).userName(request.username())
                    .balance(balanceList).sessions(new ArrayList<>(List.of(createSession(request)))).build();

                     return processAccountingRequest(newUserSessionData, request,traceId);

                });
    }

    private Uni<Void> processAccountingRequest(
            UserSessionData userData, AccountingRequestDto request,String traceId) {
        long startTime = System.currentTimeMillis();
        log.infof("TraceId: %s Processing interim accounting request for user: %s, sessionId: %s",
                traceId,request.username(), request.sessionId());
        Session session = findSession(userData, request.sessionId());
        if (session == null) {
            session = createSession(request);
        }

        // Early return if session time hasn't increased
        if (request.sessionTime() <= session.getSessionTime()) {
            log.warnf("TraceId: %s Duplicate Session time unchanged for sessionId: %s", traceId,request.sessionId());
            return Uni.createFrom().voidItem();

        } else {
            Session finalSession = session;
            return accountingUtil.updateSessionAndBalance(userData, session, request,null)
                    .onItem().transformToUni(updateResult -> {  // Changed from transform to transformToUni
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
                request.nasIP()

        );
    }

    private void generateAndSendCDR(AccountingRequestDto request, Session session) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildInterimCDREvent);
    }


}