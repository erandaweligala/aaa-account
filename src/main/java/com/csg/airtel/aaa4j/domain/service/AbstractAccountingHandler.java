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

/**
 * Base class for accounting handlers (Interim and Stop).
 * Contains common logic for handling new session usage and user service details.
 */
@ApplicationScoped
public class AbstractAccountingHandler {

    private static final Logger log = Logger.getLogger(AbstractAccountingHandler.class);
    private static final String NO_SERVICE_BUCKETS_MSG = "No service buckets found";

    protected final CacheClient cacheUtil;
    protected final UserBucketRepository userRepository;
    protected final AccountProducer accountProducer;
    protected final COAService coaService;

    protected AbstractAccountingHandler() {
        this.cacheUtil = null;
        this.userRepository = null;
        this.accountProducer = null;
        this.coaService = null;
    }

    @Inject
    public AbstractAccountingHandler(
            CacheClient cacheUtil,
            UserBucketRepository userRepository,
            AccountProducer accountProducer,
            COAService coaService) {
        this.cacheUtil = cacheUtil;
        this.userRepository = userRepository;
        this.accountProducer = accountProducer;
        this.coaService = coaService;
    }

    /**
     * Handles accounting for a new session where no cache entry exists for the user.
     * Checks for group ID and retrieves user service details if needed.
     */
    protected Uni<Void> handleNewSessionUsage(AccountingRequestDto request, String traceId) {
        if (log.isDebugEnabled()) {
            log.debugf("[traceId: %s] No cache entry found for user: %s", traceId, request.username());
        }

        return cacheUtil.getGroupId(request.username())
                .onItem().transformToUni(cacheGroupId -> {
                    if (cacheGroupId == null) {
                        return getUserServicesDetails(request, traceId);
                    } else {
                        return cacheUtil.getUserData(cacheGroupId)
                                .onItem().transformToUni(groupUserData -> {
                                    if (groupUserData != null) {
                                        return processAccountingRequest(groupUserData, request, traceId);
                                    } else {
                                        return getUserServicesDetails(request, traceId);
                                    }
                                });
                    }
                });
    }

    /**
     * Retrieves user service details from the repository and creates initial session data.
     * If no service buckets found, sends a disconnect response.
     */
    protected Uni<Void> getUserServicesDetails(AccountingRequestDto request, String traceId) {
        return userRepository.getServiceBucketsByUserName(request.username())
                .onItem().transformToUni(serviceBuckets -> {
                    if (serviceBuckets == null || serviceBuckets.isEmpty()) {
                        log.warnf("[traceId: %s] No service buckets found for user: %s", traceId, request.username());
                        return coaService.produceAccountingResponseEvent(
                                MappingUtil.createResponse(request, NO_SERVICE_BUCKETS_MSG,
                                        AccountingResponseEvent.EventType.COA,
                                        AccountingResponseEvent.ResponseAction.DISCONNECT),
                                createSession(request),
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

                    Session newSession = createSession(request);
                    newSession.setGroupId(groupId);
                    newSession.setAbsoluteTimeOut(serviceBuckets.getFirst().getSessionTimeout());

                    UserSessionData newUserSessionData = UserSessionData.builder()
                            .superTemplateId(templates)
                            .groupId(groupId)
                            .userName(request.username())
                            .concurrency(concurrency)
                            .balance(balanceList)
                            .sessions(new ArrayList<>(List.of(newSession)))
                            .userStatus(serviceBuckets.getFirst().getUserStatus())
                            .sessionTimeOut(serviceBuckets.getFirst().getSessionTimeout())
                            .build();

                    return processAccountingRequest(newUserSessionData, request, traceId);
                });
    }

    /**
     * Finds a session by session ID in the list of sessions.
     */
    protected Session findSessionById(List<Session> sessions, String sessionId) {
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
     * Creates a new session from the accounting request.
     * Subclasses can override this to provide handler-specific session initialization.
     */
    protected Session createSession(AccountingRequestDto request) {
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
                true,
                0,
                null,
                request.username(),
                null,
                null
        );
    }

    /**
     * Processes the accounting request with user session data.
     * Subclasses can override this to provide handler-specific processing logic.
     */
    protected Uni<Void> processAccountingRequest(
            UserSessionData userSessionData,
            AccountingRequestDto request,
            String traceId) {
        log.warnf("[traceId: %s] processAccountingRequest called on base class for user: %s",
                traceId, request.username());
        return Uni.createFrom().voidItem();
    }
}
