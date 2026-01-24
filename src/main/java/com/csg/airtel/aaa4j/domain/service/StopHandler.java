package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.*;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;


@ApplicationScoped
public class StopHandler {

    private static final Logger log = Logger.getLogger(StopHandler.class);

    private final AbstractAccountingHandler accountingHandler;
    private final CacheClient cacheUtil;
    private final AccountProducer accountProducer;
    private final AccountingUtil accountingUtil;
    private final SessionLifecycleManager sessionLifecycleManager;

    @Inject
    public StopHandler(
            AbstractAccountingHandler accountingHandler,
            CacheClient cacheUtil,
            AccountProducer accountProducer,
            AccountingUtil accountingUtil,
            SessionLifecycleManager sessionLifecycleManager) {
        this.accountingHandler = accountingHandler;
        this.cacheUtil = cacheUtil;
        this.accountProducer = accountProducer;
        this.accountingUtil = accountingUtil;
        this.sessionLifecycleManager = sessionLifecycleManager;
    }

    public Uni<Void> stopProcessing(AccountingRequestDto request,String bucketId,String traceId) {
        log.infof("[traceId: %s] Processing accounting stop for user: %s, sessionId: %s",
                traceId, request.username(), request.sessionId());
        return cacheUtil.getUserData(request.username())
                .onItem().invoke(() -> log.infof("[traceId: %s] User data retrieved for user: %s", traceId, request.username()))
                .onItem().transformToUni(userSessionData ->
                        userSessionData != null ?
                                 processAccountingStop(userSessionData, request,bucketId).invoke(() -> log.infof("[traceId: %s] Completed processing for eventType=%s, action=%s, bucketId=%s", traceId, bucketId))
                                 : accountingHandler.handleNewSessionUsage(request, traceId, this::processAccountingStop, this::createSession)
                )
                .onFailure().recoverWithUni(throwable -> {
                    log.errorf(throwable, "Error processing accounting for user: %s", request.username());
                    return Uni.createFrom().voidItem();
                });
    }

    public Uni<Void> processAccountingStop(
            UserSessionData userSessionData,AccountingRequestDto request
            ,String bucketId) {

        if (request.delayTime() > 0) {
            log.warnf("Duplicate Stop Request unchanged for sessionId: %s",request.sessionId());
            return Uni.createFrom().voidItem();

        }
        Session session = accountingHandler.findSessionById(userSessionData.getSessions(), request.sessionId());

        if (session == null) {
            log.infof( "[traceId: %s] Session not found for sessionId: %s", request.username(), request.sessionId());
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
                                        log.errorf(throwable, "Failed to produce DB write event for session: %s",
                                                request.sessionId())
                                );
                    }else {
                        return Uni.createFrom().voidItem();
                    }
                })
                .call(() -> sessionLifecycleManager.onSessionTerminated(request.username(), request.sessionId()))
                .invoke(() ->
                    //send CDR event asynchronously
                    generateAndSendCDR(request, finalSession, finalSession.getServiceId(), finalSession.getPreviousUsageBucketId())
                )
                .invoke(() -> {
                    if (log.isDebugEnabled()) {
                        log.debugf("Session and balance cleaned for session: %s", request.sessionId());
                    }
                })
                .onFailure().recoverWithUni(throwable -> {
                    log.errorf(throwable, "Failed to process accounting stop for session: %s",
                            request.sessionId());
                    return Uni.createFrom().voidItem();
                });
    }


    private Uni<UpdateResult> cleanSessionAndUpdateBalance(
            UserSessionData userSessionData,String bucketId,AccountingRequestDto request,Session session) {

        return accountingUtil.updateSessionAndBalance(userSessionData, session, request, bucketId);
    }

    private void generateAndSendCDR(AccountingRequestDto request, Session session, String serviceId, String bucketId) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildStopCDREvent, serviceId, bucketId);
    }

    private Session createSession(AccountingRequestDto request) {
        return new Session(
                request.sessionId(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                request.sessionTime(),
                0L,
                request.framedIPAddress(),
                request.nasIP(),
                request.nasPortId(),
                false,
                0,null,
                request.username(),
                null,null,
                null,
                0
        );
    }
}

