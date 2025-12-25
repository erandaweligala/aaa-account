package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.EventType;
import com.csg.airtel.aaa4j.domain.model.UpdateResult;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.domain.util.StructuredLogger;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.List;


@ApplicationScoped
public class StopHandler {

    private static final StructuredLogger log = StructuredLogger.getLogger(StopHandler.class);

    private final CacheClient cacheUtil;
    private final AccountProducer accountProducer;
    private final AccountingUtil accountingUtil;
    private final SessionLifecycleManager sessionLifecycleManager;

    @Inject
    public StopHandler(CacheClient cacheUtil, AccountProducer accountProducer, AccountingUtil accountingUtil, SessionLifecycleManager sessionLifecycleManager) {
        this.cacheUtil = cacheUtil;
        this.accountProducer = accountProducer;
        this.accountingUtil = accountingUtil;
        this.sessionLifecycleManager = sessionLifecycleManager;
    }

    public Uni<Void> stopProcessing(AccountingRequestDto request,String bucketId,String traceId) {
        long startTime = System.currentTimeMillis();

        // Set MDC context for correlation
        StructuredLogger.setContext(traceId, request.username(), request.sessionId());
        StructuredLogger.setOperation("STOP");

        log.info("Processing accounting STOP request", StructuredLogger.Fields.create()
                .add("username", request.username())
                .add("sessionId", request.sessionId())
                .add("bucketId", bucketId)
                .add("acctInputOctets", request.inputOctets())
                .add("acctOutputOctets", request.outputOctets())
                .add("acctSessionTime", request.sessionTime())
                .build());

        return cacheUtil.getUserData(request.username())
                .onItem().invoke(() -> {
                    if (log.isDebugEnabled()) {
                        log.debug("User data retrieved for STOP", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .build());
                    }
                })
                .onItem().transformToUni(userSessionData ->
                        userSessionData != null ?
                                 processAccountingStop(userSessionData, request,bucketId).invoke(() -> {
                                     long duration = System.currentTimeMillis() - startTime;
                                     log.info("Completed STOP processing", StructuredLogger.Fields.create()
                                             .add("username", request.username())
                                             .add("bucketId", bucketId)
                                             .addDuration(duration)
                                             .addStatus("success")
                                             .build());
                                 })
                                 : Uni.createFrom().voidItem()
                )
                .onFailure().recoverWithUni(throwable -> {
                    long duration = System.currentTimeMillis() - startTime;
                    log.error("Error processing accounting STOP", throwable, StructuredLogger.Fields.create()
                            .add("username", request.username())
                            .addErrorCode("STOP_PROCESSING_ERROR")
                            .addDuration(duration)
                            .addStatus("failed")
                            .add("errorType", throwable.getClass().getSimpleName())
                            .build());
                    return Uni.createFrom().voidItem();
                })
                .eventually(StructuredLogger::clearContext);
    }

    public Uni<Void> processAccountingStop(
            UserSessionData userSessionData,AccountingRequestDto request
            ,String bucketId) {

        if (request.delayTime() > 0) {
            log.warn("Duplicate STOP request detected", StructuredLogger.Fields.create()
                    .add("sessionId", request.sessionId())
                    .add("delayTime", request.delayTime())
                    .addStatus("duplicate")
                    .build());
            return Uni.createFrom().voidItem();

        }
        Session session = findSessionById(userSessionData.getSessions(), request.sessionId());

        if (session == null) {
            log.info("Session not found in cache, creating placeholder", StructuredLogger.Fields.create()
                    .add("username", request.username())
                    .add("sessionId", request.sessionId())
                    .build());
                session = createSession(request);
                session.setGroupId(userSessionData.getGroupId());
        }


        Session finalSession = session;
        return cleanSessionAndUpdateBalance(userSessionData,bucketId,request,session)
                .onFailure().recoverWithNull()
                .onItem().transformToUni(updateResult -> {
                    if(updateResult != null && updateResult.success()) {
                        DBWriteRequest dbWriteRequest = MappingUtil.createDBWriteRequest(updateResult.balance(), request.username(), request.sessionId(), EventType.UPDATE_EVENT);
                        return accountProducer.produceDBWriteEvent(dbWriteRequest)
                                .onFailure().invoke(throwable ->
                                        log.errorf(throwable.getMessage(), "Failed to produce DB write event for session: %s",
                                                request.sessionId())
                                );
                    }else {
                        return Uni.createFrom().voidItem();
                    }
                })
                .invoke(() -> userSessionData.getSessions().remove(finalSession))
                .call(() -> {
                    log.infof("[traceId: %s] Updating cache for user: %s", request.username());
                    // Update cache
                    return cacheUtil.updateUserAndRelatedCaches(request.username(), userSessionData)
                            .onFailure().invoke(throwable ->
                                    log.errorf(throwable.getMessage(), "Failed to update cache for user: %s",
                                            request.username())
                            )
                            .onFailure().recoverWithNull(); // Cache failure can still be swallowed
                })
                .call(() -> sessionLifecycleManager.onSessionTerminated(request.username(), request.sessionId()))
                .invoke(() ->
                    //send CDR event asynchronously
                    generateAndSendCDR(request, finalSession)
                )
                .invoke(() -> {
                    if (log.isDebugEnabled()) {
                        log.debugf("Session and balance cleaned for session: %s", request.sessionId());
                    }
                })
                .onFailure().recoverWithUni(throwable -> {
                    log.errorf(throwable.getMessage(), "Failed to process accounting stop for session: %s",
                            request.sessionId());
                    return Uni.createFrom().voidItem();
                });
    }

    private Session findSessionById(List<Session> sessions, String sessionId) {
        for (Session session : sessions) {
            if (session.getSessionId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    private Uni<UpdateResult> cleanSessionAndUpdateBalance(
            UserSessionData userSessionData,String bucketId,AccountingRequestDto request,Session session) {

        return accountingUtil.updateSessionAndBalance(userSessionData, session, request, bucketId);
    }

    private void generateAndSendCDR(AccountingRequestDto request, Session session) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildStopCDREvent);
    }

    private Session createSession(AccountingRequestDto request) {
        return new Session(
                request.sessionId(),
                LocalDateTime.now(),
                null,
                request.sessionTime(),
                0L,
                request.framedIPAddress(),
                request.nasIP(),
                request.nasPortId(),
                false,
                0,null
        );
    }

}

