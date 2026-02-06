package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.cdr.AccountingCDREvent;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CoAHttpClient;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;



@ApplicationScoped
public class COAService {
    private static final Logger log = Logger.getLogger(COAService.class);
    private static final String CLASS_NAME = "COAService";

    private final AccountProducer accountProducer;
    private final MonitoringService monitoringService;
    private final CoAHttpClient coaHttpClient;

    public COAService(AccountProducer accountProducer,
                      MonitoringService monitoringService,
                      CoAHttpClient coaHttpClient) {
        this.accountProducer = accountProducer;
        this.monitoringService = monitoringService;
        this.coaHttpClient = coaHttpClient;
    }

    public Uni<Void> clearAllSessionsAndSendCOAMassageQue(UserSessionData userSessionData, String username,String sessionId) {
        List<Session> sessions = userSessionData.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        if(sessionId != null){
            sessions = sessions.stream().filter(rs -> Objects.equals(rs.getSessionId(), sessionId))
                    .toList();
        }

        // Use merge instead of concatenate for parallel execution (better throughput)
        return Multi.createFrom().iterable(sessions)
                .onItem().transformToUni(session -> {
                        // Generate CoA Request CDR before sending the disconnect event
                        generateAndSendCoaRequestCDR(session, username);
                        return accountProducer.produceAccountingResponseEvent(
                                        MappingUtil.createResponse(
                                                session.getSessionId(),
                                                AppConstant.DISCONNECT_ACTION,
                                                session.getNasIp(),
                                                session.getFramedId(),
                                                session.getUserName() !=null ?session.getUserName():username
                                        )
                                )
                                .invoke(() -> {
                                    // Record COA request metric
                                    monitoringService.recordCOARequest();
                                    generateAndSendCoaDisconnectCDR(session, username);
                                })
                                .onFailure().retry()
                                .withBackOff(Duration.ofMillis(AppConstant.COA_RETRY_INITIAL_BACKOFF_MS), Duration.ofSeconds(AppConstant.COA_RETRY_MAX_BACKOFF_SECONDS))
                                .atMost(AppConstant.COA_RETRY_MAX_ATTEMPTS)
                                .onFailure().invoke(failure ->
                                    LoggingUtil.logDebug(log, CLASS_NAME, "clearAllSessionsAndSendCOAMassageQue", "Failed to produce disconnect event for session: %s",
                                            session.getSessionId())
                                )
                                .onFailure().recoverWithNull();
                })
                .merge() // Parallel execution instead of sequential
                .collect().asList()
                .ifNoItem().after(Duration.ofSeconds(AppConstant.COA_TIMEOUT_SECONDS)).fail()
                .replaceWithVoid();
    }

    /**
     * Generate and send COA Disconnect CDR event asynchronously.
     * This method builds a CDR event for a COA disconnect operation and sends it to the accounting system.
     *
     * @param session the session being disconnected
     * @param username the username associated with the session
     */
    private void generateAndSendCoaDisconnectCDR(Session session, String username) {
        try {
            AccountingCDREvent cdrEvent = CdrMappingUtil.buildCoaDisconnectCDREvent(session, username);
            accountProducer.produceAccountingCDREvent(cdrEvent)
                    .subscribe()
                    .with(
                            success -> LoggingUtil.logInfo(log, CLASS_NAME, "generateAndSendCoaDisconnectCDR", "COA Disconnect CDR event sent successfully for session: %s, user: %s",
                                    session.getSessionId(), username),
                            failure -> LoggingUtil.logError(log, CLASS_NAME, "generateAndSendCoaDisconnectCDR", failure, "Failed to send COA Disconnect CDR event for session: %s, user: %s",
                                    session.getSessionId(), username)
                    );
        } catch (Exception e) {
            LoggingUtil.logError(log, CLASS_NAME, "generateAndSendCoaDisconnectCDR", e, "Error building COA Disconnect CDR event for session: %s, user: %s",
                    session.getSessionId(), username);
        }
    }

    /**
     * Generate and send COA Request CDR event asynchronously.
     * This method builds a CDR event when a COA disconnect request is about to be sent to the NAS.
     *
     * @param session the session for which CoA request is being initiated
     * @param username the username associated with the session
     */
    private void generateAndSendCoaRequestCDR(Session session, String username) {
        try {
            AccountingCDREvent cdrEvent = CdrMappingUtil.buildCoaRequestCDREvent(session, username);
            accountProducer.produceAccountingCDREvent(cdrEvent)
                    .subscribe()
                    .with(
                            success -> LoggingUtil.logInfo(log, CLASS_NAME, "generateAndSendCoaRequestCDR", "COA Request CDR event sent successfully for session: %s, user: %s",
                                    session.getSessionId(), username),
                            failure -> LoggingUtil.logError(log, CLASS_NAME, "generateAndSendCoaRequestCDR", failure, "Failed to send COA Request CDR event for session: %s, user: %s",
                                    session.getSessionId(), username)
                    );
        } catch (Exception e) {
            LoggingUtil.logError(log, CLASS_NAME, "generateAndSendCoaRequestCDR", e, "Error building COA Request CDR event for session: %s, user: %s",
                    session.getSessionId(), username);
        }
    }

    /**
     * Generate and send COA Response CDR event asynchronously.
     * This method builds a CDR event when a COA disconnect response (ACK/NAK) is received from the NAS.
     *
     * @param session the session for which CoA response was received
     * @param username the username associated with the session
     * @param responseStatus the response status from NAS (e.g. "ACK", "NAK", "FAILED")
     */
    private void generateAndSendCoaResponseCDR(Session session, String username, String responseStatus) {
        try {
            AccountingCDREvent cdrEvent = CdrMappingUtil.buildCoaResponseCDREvent(session, username, responseStatus);
            accountProducer.produceAccountingCDREvent(cdrEvent)
                    .subscribe()
                    .with(
                            success -> LoggingUtil.logInfo(log, CLASS_NAME, "generateAndSendCoaResponseCDR", "COA Response CDR event sent successfully for session: %s, user: %s, status: %s",
                                    session.getSessionId(), username, responseStatus),
                            failure -> LoggingUtil.logError(log, CLASS_NAME, "generateAndSendCoaResponseCDR", failure, "Failed to send COA Response CDR event for session: %s, user: %s, status: %s",
                                    session.getSessionId(), username, responseStatus)
                    );
        } catch (Exception e) {
            LoggingUtil.logError(log, CLASS_NAME, "generateAndSendCoaResponseCDR", e, "Error building COA Response CDR event for session: %s, user: %s, status: %s",
                    session.getSessionId(), username, responseStatus);
        }
    }

    /**
     * Produce accounting response event and generate COA disconnect CDR.
     * This method sends disconnect request to NAS for session rejection scenarios.
     * Used when rejecting new sessions (concurrency exceeded, no balance, etc.).
     *
     * @param event the accounting response event to send
     * @param session the session being rejected
     * @param username the username associated with the session
     * @return Uni<Void> after the disconnect request is sent
     */
    public Uni<Void> produceAccountingResponseEvent(AccountingResponseEvent event, Session session, String username) {
        // Generate CoA Request CDR before sending the disconnect request
        generateAndSendCoaRequestCDR(session, username);
        return coaHttpClient.sendDisconnect(event)
                .onItem().invoke(response -> {
                    if (response.isAck()) {
                        LoggingUtil.logInfo(log, CLASS_NAME, "produceAccountingResponseEvent", "CoA disconnect ACK received for rejected session: %s", session.getSessionId());
                        monitoringService.recordCOARequest();
                        generateAndSendCoaResponseCDR(session, username, "ACK");
                    } else {
                        LoggingUtil.logWarn(log, CLASS_NAME, "produceAccountingResponseEvent", "CoA disconnect NAK/Failed for rejected session: %s, status: %s",
                                session.getSessionId(), response.status());
                        generateAndSendCoaResponseCDR(session, username, response.status());
                    }
                })
                .onFailure().invoke(failure -> {
                        LoggingUtil.logError(log, CLASS_NAME, "produceAccountingResponseEvent", failure, "HTTP CoA disconnect failed for session: %s", session.getSessionId());
                        generateAndSendCoaResponseCDR(session, username, "FAILED");
                })
                .replaceWithVoid();
    }

    /**
     * Response data holder for CoA disconnect operations.
     * Contains the session ID and whether it received ACK response.
     */
    private record CoAResult(String sessionId, boolean isAck) {}

    /**
     * Send CoA Disconnect via HTTP (non-blocking).
     * This method sends CoA disconnect requests directly to NAS via HTTP.
     * Returns UserSessionData with sessions removed based on NAK responses:
     * - ACK: Session remains in the list (successfully disconnected)
     * - NAK: Session removed from the list (failed to disconnect)
     *
     * @param userSessionData the user session data
     * @param username the username
     * @param sessionId specific session to disconnect (null for all sessions)
     * @return Uni<UserSessionData> with sessions removed/kept based on NAK/ACK responses
     */
    public Uni<UserSessionData> clearAllSessionsAndSendCOA(UserSessionData userSessionData, String username, String sessionId) {
        List<Session> sessions = userSessionData.getSessions();
        if (sessions == null || sessions.isEmpty()) {
            LoggingUtil.logDebug(log, CLASS_NAME, "clearAllSessionsAndSendCOA", "No sessions to disconnect for user: %s", username);
            return Uni.createFrom().item(userSessionData);
        }

        // Filter sessions if specific sessionId is provided
        List<Session> sessionsToDisconnect;
        if (sessionId != null) {
            sessionsToDisconnect = sessions.stream()
                    .filter(s -> Objects.equals(s.getSessionId(), sessionId))
                    .toList();
        } else {
            sessionsToDisconnect = sessions;
        }

        if (sessionsToDisconnect.isEmpty()) {
            LoggingUtil.logDebug(log, CLASS_NAME, "clearAllSessionsAndSendCOA", "No matching sessions found for user: %s, sessionId: %s", username, sessionId);
            return Uni.createFrom().item(userSessionData);
        }

        LoggingUtil.logInfo(log, CLASS_NAME, "clearAllSessionsAndSendCOA", "Sending HTTP CoA disconnect for user: %s, session count: %d", username, sessionsToDisconnect.size());

        // Send HTTP disconnect for each session in parallel (non-blocking)
        return Multi.createFrom().iterable(sessionsToDisconnect)
                .onItem().transformToUni(session -> {
                    AccountingResponseEvent request = MappingUtil.createResponse(
                            session.getSessionId(),
                            AppConstant.DISCONNECT_ACTION,
                            session.getNasIp(),
                            session.getFramedId(),
                            session.getUserName() != null ? session.getUserName() : username);

                    // Generate CoA Request CDR before sending the HTTP disconnect
                    generateAndSendCoaRequestCDR(session, username);

                    // Send HTTP request and track ACK/NAK result
                    return coaHttpClient.sendDisconnect(request)
                            .onItem().transform(response -> {
                                if (response.isAck()) {
                                    LoggingUtil.logInfo(log, CLASS_NAME, "clearAllSessionsAndSendCOA", "CoA disconnect ACK received for session: %s", session.getSessionId());
                                    monitoringService.recordCOARequest();
                                    generateAndSendCoaResponseCDR(session, username, "ACK");
                                    return new CoAResult(session.getSessionId(), true);
                                } else {
                                    LoggingUtil.logWarn(log, CLASS_NAME, "clearAllSessionsAndSendCOA", "CoA disconnect NAK/Failed for session: %s, status: %s, message: %s",
                                            session.getSessionId(), response.status(), response.message());
                                    generateAndSendCoaResponseCDR(session, username, response.status());
                                    return new CoAResult(session.getSessionId(), false);
                                }
                            })
                            .onFailure().invoke(failure -> {
                                    LoggingUtil.logError(log, CLASS_NAME, "clearAllSessionsAndSendCOA", failure, "HTTP CoA disconnect failed for session: %s", session.getSessionId());
                                    generateAndSendCoaResponseCDR(session, username, "FAILED");
                            })
                            .onFailure().recoverWithItem(new CoAResult(session.getSessionId(), false)); // NAK on failure
                })
                .merge() // Parallel execution for all sessions
                .collect().asList()
                .onItem().transform(results -> {
                    // Collect NAK session IDs (failed disconnects)
                    List<String> nakSessionIds = results.stream()
                            .filter(result -> !result.isAck())
                            .map(CoAResult::sessionId)
                            .toList();

                    if (nakSessionIds.isEmpty()) {
                        LoggingUtil.logInfo(log, CLASS_NAME, "clearAllSessionsAndSendCOA", "No sessions received NAK for user: %s, returning original data", username);
                        return userSessionData;
                    }

                    // Remove sessions that got NAK from the list
                    List<Session> remainingSessions = userSessionData.getSessions().stream()
                            .filter(s -> !nakSessionIds.contains(s.getSessionId()))
                            .toList();

                    LoggingUtil.logInfo(log, CLASS_NAME, "clearAllSessionsAndSendCOA", "Removed %d NAK sessions from user: %s, remaining sessions: %d",
                            nakSessionIds.size(), username, remainingSessions.size());

                    // Return updated UserSessionData with NAK sessions removed
                    return userSessionData.toBuilder()
                            .sessions(remainingSessions)
                            .build();
                });
    }

}
