package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.EventType;
import com.csg.airtel.aaa4j.domain.model.UpdateResult;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;


@ApplicationScoped
public class COADisconnectRequestHandler {

    private static final Logger log = Logger.getLogger(COADisconnectRequestHandler.class);

    private final CacheClient cacheUtil;
    private final AccountProducer accountProducer;
    private final AccountingUtil accountingUtil;
    private final SessionLifecycleManager sessionLifecycleManager;

    @Inject
    public COADisconnectRequestHandler(CacheClient cacheUtil, AccountProducer accountProducer,
                                       AccountingUtil accountingUtil, SessionLifecycleManager sessionLifecycleManager) {
        this.cacheUtil = cacheUtil;
        this.accountProducer = accountProducer;
        this.accountingUtil = accountingUtil;
        this.sessionLifecycleManager = sessionLifecycleManager;
    }

    public Uni<Void> processCoaDisconnectRequest(AccountingRequestDto request, String traceId) {
        log.infof("[traceId: %s] Processing COA disconnect request for user: %s, sessionId: %s",
                traceId, request.username(), request.sessionId());

        return cacheUtil.getUserData(request.username())
                .onItem().invoke(() -> log.infof("[traceId: %s] User data retrieved for user: %s", traceId, request.username()))
                .onItem().transformToUni(userSessionData ->
                        userSessionData != null ?
                                processCoaDisconnect(userSessionData, request, traceId)
                                : handleUserNotFound(request, traceId)
                )
                .onFailure().recoverWithUni(throwable -> {
                    log.errorf(throwable, "[traceId: %s] Error processing COA disconnect request for user: %s",
                            traceId, request.username());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> processCoaDisconnect(UserSessionData userSessionData, AccountingRequestDto request, String traceId) {
        Session session = findSessionById(userSessionData.getSessions(), request.sessionId());

        if (session == null) {
            log.warnf("[traceId: %s] Session not found for COA disconnect request - sessionId: %s, user: %s",
                    traceId, request.sessionId(), request.username());
            return Uni.createFrom().voidItem();
        }

        log.infof("[traceId: %s] Terminating session %s for user %s due to COA disconnect request",
                traceId, request.sessionId(), request.username());

        Session finalSession = session;
        return cleanSessionAndUpdateBalance(userSessionData, request, session, traceId)
                .onFailure().recoverWithNull()
                .onItem().transformToUni(updateResult -> {
                    if (updateResult != null && updateResult.success()) {
                        DBWriteRequest dbWriteRequest = MappingUtil.createDBWriteRequest(
                                updateResult.balance(),
                                request.username(),
                                request.sessionId(),
                                EventType.UPDATE_EVENT);
                        return accountProducer.produceDBWriteEvent(dbWriteRequest)
                                .onFailure().invoke(throwable ->
                                        log.errorf(throwable, "[traceId: %s] Failed to produce DB write event for session: %s",
                                                traceId, request.sessionId())
                                );
                    } else {
                        return Uni.createFrom().voidItem();
                    }
                })
                .invoke(() -> userSessionData.getSessions().remove(finalSession))
                .call(() -> {
                    log.infof("[traceId: %s] Updating cache for user: %s after COA disconnect", traceId, request.username());
                    return cacheUtil.updateUserAndRelatedCaches(request.username(), userSessionData)
                            .onFailure().invoke(throwable ->
                                    log.errorf(throwable, "[traceId: %s] Failed to update cache for user: %s",
                                            traceId, request.username())
                            )
                            .onFailure().recoverWithNull();
                })
                .call(() -> sessionLifecycleManager.onSessionTerminated(request.username(), request.sessionId()))
                .invoke(() -> generateAndSendCDR(request, finalSession, traceId))
                .invoke(() -> log.infof("[traceId: %s] Successfully processed COA disconnect request for session: %s",
                        traceId, request.sessionId()))
                .onFailure().recoverWithUni(throwable -> {
                    log.errorf(throwable, "[traceId: %s] Failed to process COA disconnect request for session: %s",
                            traceId, request.sessionId());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> handleUserNotFound(AccountingRequestDto request, String traceId) {
        log.warnf("[traceId: %s] User not found in cache for COA disconnect request - user: %s, sessionId: %s",
                traceId, request.username(), request.sessionId());
        return Uni.createFrom().voidItem();
    }

    private Session findSessionById(List<Session> sessions, String sessionId) {
        for (Session session : sessions) {
            if (session.getSessionId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    private Uni<UpdateResult> cleanSessionAndUpdateBalance(UserSessionData userSessionData,
                                                           AccountingRequestDto request,
                                                           Session session,
                                                           String traceId) {
        log.infof("[traceId: %s] Cleaning session and updating balance for sessionId: %s", traceId, request.sessionId());
        return accountingUtil.updateSessionAndBalance(userSessionData, session, request, null);
    }

    private void generateAndSendCDR(AccountingRequestDto request, Session session, String traceId) {
        log.infof("[traceId: %s] Generating CDR for COA disconnect - sessionId: %s", traceId, request.sessionId());
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer,
                (req, sess) -> CdrMappingUtil.buildCOADisconnectCDREvent(sess, req.username()));
    }
}
