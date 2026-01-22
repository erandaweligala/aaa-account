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

import java.time.LocalDateTime;
import java.util.List;


@ApplicationScoped
public class StopHandler {

    private static final Logger log = Logger.getLogger(StopHandler.class);

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
        log.infof("[traceId: %s] Processing accounting stop for user: %s, sessionId: %s",
                traceId, request.username(), request.sessionId());
        return cacheUtil.getUserData(request.username())
                .onItem().invoke(() -> log.infof("[traceId: %s] User data retrieved for user: %s", traceId, request.username()))
                .onItem().transformToUni(userSessionData ->
                        userSessionData != null ?
                                 processAccountingStop(userSessionData, request,bucketId).invoke(() -> log.infof("[traceId: %s] Completed processing for eventType=%s, action=%s, bucketId=%s", traceId, bucketId))
                                 : Uni.createFrom().voidItem()
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
        Session session = findSessionById(userSessionData.getSessions(), request.sessionId());

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
                .invoke(() -> userSessionData.getSessions().remove(finalSession))
                .call(() -> {
                    log.infof("[traceId: %s] Updating cache for user: %s", request.username());

                    String groupId = userSessionData.getGroupId();
                    boolean hasGroupId = groupId != null && !"1".equals(groupId);

                    // Update cache - both user and group if applicable
                    if (hasGroupId) {
                        return updateUserAndGroupCaches(request.username(), groupId,
                                userSessionData, finalSession.getSessionId())
                                .onFailure().invoke(throwable ->
                                        log.errorf(throwable, "Failed to update cache for user: %s",
                                                request.username())
                                )
                                .onFailure().recoverWithNull(); // Cache failure can still be swallowed
                    } else {
                        return cacheUtil.updateUserAndRelatedCaches(request.username(), userSessionData)
                                .onFailure().invoke(throwable ->
                                        log.errorf(throwable, "Failed to update cache for user: %s",
                                                request.username())
                                )
                                .onFailure().recoverWithNull(); // Cache failure can still be swallowed
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
                null
        );
    }

    /**
     * Update both user and group caches when a user's session is removed.
     * Removes the specified session from the group's session list.
     *
     * @param username the username of the user
     * @param groupUsername the username of the group
     * @param updatedUserData the updated user session data
     * @param sessionId the session ID that was removed
     * @return Uni<Void> when both caches are updated
     */
    private Uni<Void> updateUserAndGroupCaches(String username, String groupUsername,
                                               UserSessionData updatedUserData,
                                               String sessionId) {
        // Fetch current group data from cache
        return cacheUtil.getUserData(groupUsername)
                .onFailure().recoverWithNull()
                .onItem().transformToUni(existingGroupData -> {
                    if (existingGroupData != null && sessionId != null) {
                        // Remove session from group's session list
                        List<Session> groupSessions = existingGroupData.getSessions();
                        if (groupSessions != null && !groupSessions.isEmpty()) {
                            groupSessions.removeIf(session -> sessionId.equals(session.getSessionId()));

                            if (log.isDebugEnabled()) {
                                log.debugf("Removed session %s from group cache for groupId: %s",
                                        sessionId, groupUsername);
                            }
                        }

                        // Update both user and group caches in parallel
                        return Uni.combine().all().unis(
                                cacheUtil.updateUserAndRelatedCaches(groupUsername, existingGroupData)
                                        .onFailure().invoke(err ->
                                                log.errorf(err, "Error updating group cache for groupId: %s", groupUsername)),
                                cacheUtil.updateUserAndRelatedCaches(username, updatedUserData)
                                        .onFailure().invoke(err ->
                                                log.errorf(err, "Error updating user cache for user: %s", username))
                        ).discardItems();
                    } else {
                        // Group data not found or no session to remove, just update user cache
                        if (log.isDebugEnabled()) {
                            log.debugf("Group data not found for groupId: %s, updating only user cache", groupUsername);
                        }
                        return cacheUtil.updateUserAndRelatedCaches(username, updatedUserData);
                    }
                });
    }

}

