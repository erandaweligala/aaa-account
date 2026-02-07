package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
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
import java.util.function.Function;

/**
 * Service class for common accounting handler operations.
 * Provides shared logic for handling new session usage and user service details.
 * Use composition (inject this class) instead of inheritance.
 */
@ApplicationScoped
public class AbstractAccountingHandler {

    private static final Logger log = Logger.getLogger(AbstractAccountingHandler.class);
    private static final String NO_SERVICE_BUCKETS_MSG = "No service buckets found";
    private static final String M_NEW_SESSION = "handleNewSessionUsage";
    private static final String M_USER_DETAILS = "getUserServicesDetails";

    private final CacheClient cacheUtil;
    private final UserBucketRepository userRepository;
    private final COAService coaService;

    @Inject
    public AbstractAccountingHandler(
            CacheClient cacheUtil,
            UserBucketRepository userRepository,
            COAService coaService) {
        this.cacheUtil = cacheUtil;
        this.userRepository = userRepository;
        this.coaService = coaService;
    }

    /**
     * Functional interface for processing accounting requests.
     */
    @FunctionalInterface
    public interface AccountingRequestProcessor {
        Uni<Void> process(UserSessionData userSessionData, AccountingRequestDto request, String groupId);
    }


    /**
     * Handles accounting for a new session where no cache entry exists for the user.
     * Checks for group ID and retrieves user service details if needed.
     *
     * @param request the accounting request
     * @param traceId the trace ID for logging
     * @param processor the callback to process the accounting request
     * @param sessionCreator function to create a new session from the request
     */
    public Uni<Void> handleNewSessionUsage(
            AccountingRequestDto request,
            String traceId,
            AccountingRequestProcessor processor,
            Function<AccountingRequestDto, Session> sessionCreator) {
        LoggingUtil.logDebug(log, M_NEW_SESSION, "No cache entry found for user: %s", request.username());

        return cacheUtil.getGroupId(request.username())
                .onItem().transformToUni(cacheGroupId -> {

                    if (cacheGroupId == null) {
                        return getUserServicesDetails(request, traceId, processor, sessionCreator);
                    } else {
                        int p1 = cacheGroupId.indexOf(',');
                        String groupId = cacheGroupId.substring(0, p1);
                        return cacheUtil.getUserData(groupId)
                                .onItem().transformToUni(groupUserData -> {
                                    if (groupUserData != null) {
                                        return processor.process(groupUserData, request, cacheGroupId);
                                    } else {
                                        return getUserServicesDetails(request, traceId, processor, sessionCreator);
                                    }
                                });
                    }
                });
    }

    /**
     * Retrieves user service details from the repository and creates initial session data.
     * If no service buckets found, sends a disconnect response.
     *
     * @param request the accounting request
     * @param traceId the trace ID for logging
     * @param processor the callback to process the accounting request
     * @param sessionCreator function to create a new session from the request
     */
    public Uni<Void> getUserServicesDetails(
            AccountingRequestDto request,
            String traceId,
            AccountingRequestProcessor processor,
            Function<AccountingRequestDto, Session> sessionCreator) {
        return userRepository.getServiceBucketsByUserName(request.username())
                .onItem().transformToUni(serviceBuckets -> {
                    if (serviceBuckets == null || serviceBuckets.isEmpty()) {
                        LoggingUtil.logWarn(log, M_USER_DETAILS, "No service buckets found for user: %s", request.username());
                        return coaService.produceAccountingResponseEvent(
                                MappingUtil.createResponse(request, NO_SERVICE_BUCKETS_MSG,
                                        AccountingResponseEvent.EventType.COA,
                                        AccountingResponseEvent.ResponseAction.DISCONNECT),
                                sessionCreator.apply(request),
                                request.username());
                    }

                    int bucketCount = serviceBuckets.size();
                    List<Balance> balanceList = new ArrayList<>(bucketCount);
                    String groupId = null;
                    long concurrency = 0;
                    Long templates = null;

                    for (ServiceBucketInfo bucket : serviceBuckets) {
                        if (!Objects.equals(bucket.getBucketUser(), request.username())) {
                            groupId = bucket.getBucketUser();
                        }
                        concurrency = bucket.getConcurrency();
                        templates = bucket.getNotificationTemplates();
                        balanceList.add(MappingUtil.createBalance(bucket));
                    }

                    String userStatus = serviceBuckets.getFirst().getUserStatus();
                    String sessionTimeout = serviceBuckets.getFirst().getSessionTimeout();
                    Session newSession = sessionCreator.apply(request);
                    newSession.setGroupId(groupId);
                    newSession.setAbsoluteTimeOut(sessionTimeout);
                    newSession.setUserStatus(userStatus);
                    newSession.setUserConcurrency(concurrency);

                    UserSessionData newUserSessionData = UserSessionData.builder()
                            .superTemplateId(templates)
                            .groupId(groupId)
                            .userName(request.username())
                            .concurrency(concurrency)
                            .balance(balanceList)
                            .sessions(new ArrayList<>(List.of(newSession)))
                            .userStatus(userStatus)
                            .sessionTimeOut(sessionTimeout)
                            .build();

                    return processor.process(newUserSessionData, request, traceId);
                });
    }

    /**
     * Finds a session by session ID in the list of sessions.
     */
    public Session findSessionById(List<Session> sessions, String sessionId) {
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

    /**
     * Creates a default session from the accounting request.
     * Handlers should provide their own session creation logic via sessionCreator parameter.
     */
    public Session createDefaultSession(AccountingRequestDto request) {
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
                0,
                0,
                null,
                request.username(),
                null,
                null,
                null,
                0
        );
    }
}
