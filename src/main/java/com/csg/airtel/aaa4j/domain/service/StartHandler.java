package com.csg.airtel.aaa4j.domain.service;


import com.csg.airtel.aaa4j.application.common.LoggingUtil;
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
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@ApplicationScoped
public class StartHandler {
    private static final Logger log = Logger.getLogger(StartHandler.class);

    private static final String M_START = "processStart";
    private static final String M_VALIDATE_BALANCE = "validateBalanceAndSession";
    private static final String M_PROCESS_SESSION = "processSessionWithHighestPriority";
    private static final String M_UPDATE_CACHES = "updateCachesForSession";
    private static final String M_UPDATE_USER_GROUP = "updateUserAndGroupCaches";
    private static final String M_UPDATE_BOTH = "updateBothCaches";
    private static final String M_UPDATE_USER_ONLY = "updateUserCacheOnly";
    private static final String M_HANDLE_NEW_SESSION = "handleNewUserSession";
    private static final String M_HANDLE_NO_BUCKETS = "handleNoServiceBuckets";
    private static final String M_HANDLE_ZERO_QUOTA = "handleZeroQuota";
    private static final String M_CREATE_STORE_SESSION = "createAndStoreNewSession";
    private static final String M_HANDLE_NO_BALANCE = "handleNoValidBalance";
    private static final String M_STORE_USER_DATA = "storeUserSessionData";
    private static final String M_STORE_NEW_GROUP = "storeNewGroupData";
    private static final String M_UPDATE_EXISTING_GROUP = "updateExistingGroupData";

    private final CacheClient utilCache;
    private final UserBucketRepository userRepository;
    private final AccountProducer  accountProducer;
    private final AccountingUtil accountingUtil;
    private final SessionLifecycleManager sessionLifecycleManager;
    private final COAService coaService;

    @Inject
    public StartHandler(CacheClient utilCache, UserBucketRepository userRepository, AccountProducer accountProducer, AccountingUtil accountingUtil, SessionLifecycleManager sessionLifecycleManager, COAService coaService) {
        this.utilCache = utilCache;
        this.userRepository = userRepository;
        this.accountProducer = accountProducer;
        this.accountingUtil = accountingUtil;
        this.sessionLifecycleManager = sessionLifecycleManager;
        this.coaService = coaService;
    }

    public Uni<Void> processAccountingStart(AccountingRequestDto request,String traceId) {
        LoggingUtil.logDebug(log, M_START, "Processing accounting start for user: %s, sessionId: %s",
                request.username(), request.sessionId());

        return utilCache.getUserData(request.username())
                .onItem().transformToUni(userSessionData -> {
                    if (userSessionData == null) {
                        LoggingUtil.logDebug(log, M_START, "No cache entry for user: %s", request.username());
                        return handleNewUserSession(request);
                    } else {
                        return handleExistingUserSession(request, userSessionData);
                    }
                })
                .onFailure().recoverWithUni(throwable -> {
                    // Handle circuit breaker open specifically - service temporarily unavailable
                    if (throwable instanceof CircuitBreakerOpenException) {
                        LoggingUtil.logError(log, M_START, null, "Cache service circuit breaker is OPEN for user: %s. " +
                                        "Service temporarily unavailable due to high tps or Redis connectivity issues.",
                                request.username());

                    }
                    // Handle other errors
                    LoggingUtil.logError(log, M_START, throwable, "Error processing accounting start for user: %s",
                            request.username());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> handleExistingUserSession(
            AccountingRequestDto request,
            UserSessionData userSessionData) {
        return retrieveGroupBalances(userSessionData)
                .onItem().transformToUni(balanceList ->
                        processExistingSessionWithBalances(request, userSessionData, balanceList));
    }

    private Uni<List<Balance>> retrieveGroupBalances(UserSessionData userSessionData) {
        String groupId = userSessionData.getGroupId();
        boolean isGroupUser = groupId != null && !groupId.equals("1");

        if (isGroupUser) {
            return utilCache.getUserData(groupId)
                    .onItem().transform(UserSessionData::getBalance);
        }
        return Uni.createFrom().item(userSessionData.getBalance());
    }

    private Uni<Void> processExistingSessionWithBalances(
            AccountingRequestDto request,
            UserSessionData userSessionData,
            List<Balance> balanceList) {


        List<Balance> combinedBalances = combineBalances(userSessionData.getBalance(), balanceList);

        Uni<Void> validationResult = validateBalanceAndSession(request, userSessionData, combinedBalances);
        if (validationResult != null) {
            return validationResult;
        }

        return accountingUtil.findBalanceWithHighestPriority(combinedBalances, null)
                .onItem().transformToUni(highestPriorityBalance ->
                        processSessionWithHighestPriority(request, userSessionData, highestPriorityBalance));
    }

    private List<Balance> combineBalances(List<Balance> userBalances, List<Balance> additionalBalances) {
        List<Balance> combined = new ArrayList<>(userBalances);
        if (additionalBalances != null && !additionalBalances.isEmpty()) {
            combined.addAll(additionalBalances);
        }
        return combined;
    }

    private Uni<Void> validateBalanceAndSession(
            AccountingRequestDto request,
            UserSessionData userSessionData,
            List<Balance> combinedBalances) {

        double availableBalance = calculateAvailableBalance(combinedBalances);
        if (availableBalance <= 0) {
            LoggingUtil.logWarn(log, M_VALIDATE_BALANCE, "User: %s has exhausted their data balance. Cannot start new session.", request.username());
            return coaService.produceAccountingResponseEvent(
                    MappingUtil.createResponse(request, "Data balance exhausted",
                            AccountingResponseEvent.EventType.COA,
                            AccountingResponseEvent.ResponseAction.DISCONNECT),
                    createSession(request),
                    request.username());
        }

        if (sessionAlreadyExists(userSessionData, request.sessionId())) {
            LoggingUtil.logInfo(log, M_VALIDATE_BALANCE, "Session already exists for user: %s, sessionId: %s",
                    request.username(), request.sessionId());
            return Uni.createFrom().voidItem();
        }

        return null;
    }

    private boolean sessionAlreadyExists(UserSessionData userSessionData, String sessionId) {
        return userSessionData.getSessions()
                .stream()
                .anyMatch(session -> session.getSessionId().equals(sessionId));
    }

    private Uni<Void> processSessionWithHighestPriority(
            AccountingRequestDto request,
            UserSessionData userSessionData,
            Balance highestPriorityBalance) {

        if (highestPriorityBalance == null) {
            LoggingUtil.logWarn(log, M_PROCESS_SESSION, "No valid balance found for user: %s. Cannot start new session.", request.username());
            return coaService.produceAccountingResponseEvent(
                    MappingUtil.createResponse(request, "No valid balance found",
                            AccountingResponseEvent.EventType.COA,
                            AccountingResponseEvent.ResponseAction.DISCONNECT),
                    createSession(request),
                    request.username());
        }

        Session newSession = createSessionWithBalance(request, highestPriorityBalance);
        boolean isGroupBalance = isGroupBalance(highestPriorityBalance, request.username());
        newSession.setGroupId(userSessionData.getGroupId());
        newSession.setAbsoluteTimeOut(userSessionData.getSessionTimeOut());
        newSession.setUserStatus(userSessionData.getUserStatus());
        newSession.setUserConcurrency(userSessionData.getConcurrency());
        if (!isGroupBalance) {
            userSessionData.getSessions().add(newSession);
        }

        return updateCachesForSession(request, userSessionData, newSession, isGroupBalance)
                .call(() -> sessionLifecycleManager.onSessionCreated(request.username(), newSession))
                .invoke(() ->
                    generateAndSendCDR(request, newSession, newSession.getServiceId(), newSession.getPreviousUsageBucketId())
                )
                .onFailure().recoverWithUni(throwable -> {
                    LoggingUtil.logError(log, M_PROCESS_SESSION, throwable, "Failed to update cache for user: %s", request.username());
                    return Uni.createFrom().voidItem();
                });
    }

    private Session createSessionWithBalance(AccountingRequestDto request, Balance balance) {
        Session session = createSession(request);
        session.setPreviousUsageBucketId(balance.getBucketId());
        session.setServiceId(balance.getServiceId());
        return session;
    }

    private Uni<Void> updateCachesForSession(
            AccountingRequestDto request,
            UserSessionData userSessionData,
            Session newSession,
            boolean isHighestPriorityGroupBalance) {


        String groupId = userSessionData.getGroupId();

        if (isHighestPriorityGroupBalance && groupId != null && !groupId.equals("1")) {
            return updateUserAndGroupCaches(request, userSessionData, newSession, groupId);
        }else {
            if (hasMatchingNasPortId(userSessionData.getSessions(),request.nasPortId(),request.username()) && userSessionData.getSessions().size() >= newSession.getUserConcurrency() ) {
                LoggingUtil.logError(log, M_UPDATE_CACHES, null, "Maximum concurrent sessions exceeded for individual user: %s. Current sessions: %d, Limit: %d, nasPortId: %s",
                        request.username(), userSessionData.getSessions().size(),
                        userSessionData.getConcurrency(), request.nasPortId());
                return coaService.produceAccountingResponseEvent(
                        MappingUtil.createResponse(request, "Maximum number of concurrent sessions exceeded",
                                AccountingResponseEvent.EventType.COA,
                                AccountingResponseEvent.ResponseAction.DISCONNECT),
                        createSession(request),
                        request.username());
            }
        }

        return updateUserCacheOnly(request, userSessionData);
    }

    private Uni<Void> updateUserAndGroupCaches(
            AccountingRequestDto request,
            UserSessionData userSessionData,
            Session newSession,
            String groupId) {

        LoggingUtil.logDebug(log, M_UPDATE_USER_GROUP, "Group balance for groupId: %s", groupId);



        return utilCache.getUserData(groupId)
                .onItem().transformToUni(groupSessionData -> {
                    if (groupSessionData != null) {

                        int count = 0;
                        for (Session s : groupSessionData.getSessions()) {
                            if (s.getUserName().equals(request.username())) {
                                count++;
                            }
                        }
                        if (hasMatchingNasPortId(groupSessionData.getSessions(),request.nasPortId(),request.username()) && count >= newSession.getUserConcurrency() ) {
                            LoggingUtil.logError(log, M_UPDATE_USER_GROUP, null, "Maximum concurrent sessions exceeded for Group user: %s. Current sessions: %d, Limit: %d, nasPortId: %s",
                                    request.username(), groupSessionData.getSessions().size(),
                                    userSessionData.getConcurrency(), request.nasPortId());
                            return coaService.produceAccountingResponseEvent(
                                    MappingUtil.createResponse(request, "Maximum number of concurrent sessions exceeded",
                                            AccountingResponseEvent.EventType.COA,
                                            AccountingResponseEvent.ResponseAction.DISCONNECT),
                                    newSession,
                                    request.username());
                        }
                        addSessionToGroupData(groupSessionData, newSession);
                        return updateBothCaches(request.username(), userSessionData, groupId, groupSessionData);
                    }

                    LoggingUtil.logWarn(log, M_UPDATE_USER_GROUP, "Group data not found for groupId: %s. Only updating user data.", groupId);
                    return utilCache.updateUserAndRelatedCaches(request.username(), userSessionData,request.username());
                })
                .replaceWithVoid();
    }

    private void addSessionToGroupData(UserSessionData groupSessionData, Session newSession) {
        if (groupSessionData.getSessions() == null) {
            groupSessionData.setSessions(new ArrayList<>());
        }
        groupSessionData.getSessions().add(newSession);
    }

    private Uni<Void> updateBothCaches(
            String username,
            UserSessionData userSessionData,
            String groupId,
            UserSessionData groupSessionData) {

        return Uni.combine().all().unis(
                        utilCache.updateUserAndRelatedCaches(username, userSessionData,username),
                        utilCache.updateUserAndRelatedCaches(groupId, groupSessionData,groupId)
                ).discardItems()
                .onItem().invoke(unused ->
                        LoggingUtil.logDebug(log, M_UPDATE_BOTH, "Session added to both user: %s and group: %s", username, groupId));
    }

    private Uni<Void> updateUserCacheOnly(AccountingRequestDto request, UserSessionData userSessionData) {
        return utilCache.updateUserAndRelatedCaches(request.username(), userSessionData,request.username())
                .replaceWithVoid();
    }



    private Uni<Void> handleNewUserSession(AccountingRequestDto request) {
        return userRepository.getServiceBucketsByUserName(request.username())
                .onItem().transformToUni(serviceBuckets ->
                        processServiceBuckets(request, serviceBuckets))
                .onFailure().recoverWithUni(throwable -> {
                    LoggingUtil.logError(log, M_HANDLE_NEW_SESSION, throwable, "Error creating new user session for user: %s",
                            request.username());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> processServiceBuckets(
            AccountingRequestDto request,
            List<ServiceBucketInfo> serviceBuckets) {

        if (serviceBuckets == null || serviceBuckets.isEmpty()) {
            return handleNoServiceBuckets(request);
        }

        BucketProcessingResult result = processBucketsAndCreateBalances(request, serviceBuckets);


        List<Balance> combinedBalances = combineBalances(result.balanceList(), result.balanceGroupList());

        return accountingUtil.findBalanceWithHighestPriority(combinedBalances, null)
                .onItem().transformToUni(highestPriorityBalance ->
                        createAndStoreNewSession(request, result, highestPriorityBalance));
    }

    private Uni<Void> handleNoServiceBuckets(AccountingRequestDto request) {
        LoggingUtil.logWarn(log, M_HANDLE_NO_BUCKETS, "No service buckets found for user: %s. Cannot create session data.", request.username());
        return coaService.produceAccountingResponseEvent(
                MappingUtil.createResponse(request, "No service buckets found",
                        AccountingResponseEvent.EventType.COA,
                        AccountingResponseEvent.ResponseAction.DISCONNECT),
                createSession(request),
                request.username());
    }

    private Uni<Void> handleZeroQuota(AccountingRequestDto request) {
        LoggingUtil.logWarn(log, M_HANDLE_ZERO_QUOTA, "User: %s has zero total data quota. Cannot create session data.", request.username());
        return coaService.produceAccountingResponseEvent(
                MappingUtil.createResponse(request, "Data quota is zero",
                        AccountingResponseEvent.EventType.COA,
                        AccountingResponseEvent.ResponseAction.DISCONNECT),
                createSession(request),
                request.username());
    }

    private BucketProcessingResult processBucketsAndCreateBalances(
            AccountingRequestDto request,
            List<ServiceBucketInfo> serviceBuckets) {

        double totalQuota = 0.0;
        List<Balance> balanceList = new ArrayList<>(serviceBuckets.size());
        List<Balance> balanceGroupList = new ArrayList<>();
        String groupId = null;
        Long templates = null;
        long concurrency = 0;
        for (ServiceBucketInfo bucket : serviceBuckets) {
            Balance balance = MappingUtil.createBalance(bucket);
            groupId = getGroupId(request, bucket, balanceGroupList, balance, groupId, balanceList);
            concurrency = bucket.getConcurrency();
            templates = bucket.getNotificationTemplates();
            totalQuota += bucket.getCurrentBalance();
        }

        return new BucketProcessingResult(balanceList, balanceGroupList, groupId, totalQuota,concurrency,templates,serviceBuckets.getFirst().getUserStatus(),serviceBuckets.getFirst().getSessionTimeout());
    }



    private Uni<Void> createAndStoreNewSession(
            AccountingRequestDto request,
            BucketProcessingResult result,
            Balance highestPriorityBalance) {

        if (highestPriorityBalance == null) {
            return handleNoValidBalance(request);
        }
        if (result.totalQuota() <= 0 && !highestPriorityBalance.isUnlimited()) {
            return handleZeroQuota(request);
        }


        Session session = createSessionWithBalance(request, highestPriorityBalance);
        session.setGroupId(result.groupId());
        session.setAbsoluteTimeOut(result.sessionTimeOut);
        session.setUserStatus(result.userStatus());
        session.setUserConcurrency(result.concurrency());
        UserSessionData newUserSessionData = buildUserSessionData(
                request,result.concurrency, result.balanceList(), result.groupId(), session, highestPriorityBalance,result.templates,result.userStatus,result.sessionTimeOut);

        Uni<Void> userStorageUni = storeUserSessionData(request.username(), newUserSessionData);

        if (!result.balanceGroupList().isEmpty()) {
            userStorageUni = storeUserAndGroupData(
                    request, result, session, highestPriorityBalance, userStorageUni);
        }

        final Session finalSession = session;
        return userStorageUni
                .call(() -> sessionLifecycleManager.onSessionCreated(request.username(), finalSession))
                .onItem().invoke(unused ->
                    generateAndSendCDR(request, finalSession, finalSession.getServiceId(), finalSession.getPreviousUsageBucketId())
                );
    }

    private Uni<Void> handleNoValidBalance(AccountingRequestDto request) {
        LoggingUtil.logWarn(log, M_HANDLE_NO_BALANCE, "No valid balance found for user: %s. Cannot create session data.", request.username());
        return coaService.produceAccountingResponseEvent(
                MappingUtil.createResponse(request, "No valid balance found",
                        AccountingResponseEvent.EventType.COA,
                        AccountingResponseEvent.ResponseAction.DISCONNECT),
                createSession(request),
                request.username());
    }

    @SuppressWarnings("java:S107")
    private UserSessionData buildUserSessionData(
            AccountingRequestDto request,long concurrency,
            List<Balance> balanceList,
            String groupId,
            Session session,
            Balance highestPriorityBalance,long templates,String userStatus,String sessionTimeOut) {

        UserSessionData newUserSessionData = new UserSessionData();
        newUserSessionData.setUserStatus(userStatus);
        newUserSessionData.setGroupId(groupId);
        newUserSessionData.setUserName(request.username());
        newUserSessionData.setConcurrency(concurrency);
        newUserSessionData.setBalance(balanceList);
        newUserSessionData.setSuperTemplateId(templates);
        newUserSessionData.setSessionTimeOut(sessionTimeOut);
        session.setAbsoluteTimeOut(sessionTimeOut);
        if (!isGroupBalance(highestPriorityBalance, request.username())) {
            newUserSessionData.setSessions(new ArrayList<>(List.of(session)));
        }

        return newUserSessionData;
    }

    private Uni<Void> storeUserSessionData(String username, UserSessionData sessionData) {
        return utilCache.storeUserData(username, sessionData,username)
                .replaceWithVoid();
    }

    private Uni<Void> storeUserAndGroupData(
            AccountingRequestDto request,
            BucketProcessingResult result,
            Session session,
            Balance highestPriorityBalance,
            Uni<Void> userStorageUni) {

        UserSessionData groupSessionData = new UserSessionData();
        groupSessionData.setBalance(result.balanceGroupList());

        boolean isHighestPriorityGroupBalance = isGroupBalance(highestPriorityBalance, request.username());
        String groupId = result.groupId();

        Uni<Void> groupStorageUni = utilCache.getUserData(groupId)
                .chain(existingData -> processGroupData(
                        existingData, groupSessionData, session, isHighestPriorityGroupBalance, groupId));

        return Uni.combine().all().unis(userStorageUni, groupStorageUni).discardItems();
    }

    private Uni<Void> processGroupData(
            UserSessionData existingData,
            UserSessionData groupSessionData,
            Session session,
            boolean isHighestPriorityGroupBalance,
            String groupId) {

        if (existingData == null) {
            return storeNewGroupData(groupSessionData, session, isHighestPriorityGroupBalance, groupId);
        }

        return updateExistingGroupData(existingData, session, isHighestPriorityGroupBalance, groupId);
    }

    private Uni<Void> storeNewGroupData(
            UserSessionData groupSessionData,
            Session session,
            boolean isHighestPriorityGroupBalance,
            String groupId) {
            groupSessionData.setGroupId(groupId);
        if (isHighestPriorityGroupBalance) {
            groupSessionData.setSessions(new ArrayList<>(List.of(session)));
        } else {
            groupSessionData.setSessions(new ArrayList<>());
        }

        return utilCache.storeUserData(groupId, groupSessionData,session.getUserName())
                .onFailure().invoke(failure -> LoggingUtil.logError(log, M_STORE_NEW_GROUP, failure, "Failed to store group data for groupId: %s", groupId));
    }

    private Uni<Void> updateExistingGroupData(
            UserSessionData existingData,
            Session session,
            boolean isHighestPriorityGroupBalance,
            String groupId) {

        if (isHighestPriorityGroupBalance) {
            if (existingData.getSessions() == null) {
                existingData.setSessions(new ArrayList<>());
            }
            existingData.getSessions().add(session);
        }

        return utilCache.updateUserAndRelatedCaches(groupId, existingData,session.getUserName());
    }

    private record BucketProcessingResult(
            List<Balance> balanceList,
            List<Balance> balanceGroupList,
            String groupId,
            double totalQuota,long concurrency,long templates,String userStatus,String sessionTimeOut) {
    }

    private static String getGroupId(AccountingRequestDto request, ServiceBucketInfo bucket, List<Balance> balanceGroupList, Balance balance, String groupId, List<Balance> balanceList) {
        if (!Objects.equals(request.username(), bucket.getBucketUser())) {
            balanceGroupList.add(balance);
            groupId = bucket.getBucketUser();
        } else {
            balanceList.add(balance);
        }
        return groupId;
    }

    /**
     * Check if a balance belongs to a group (not owned by the current user).
     */
    private boolean isGroupBalance(Balance balance, String username) {
        return balance.isGroup() ||
                (balance.getBucketUsername() != null &&
                        !balance.getBucketUsername().equals(username));
    }

    private double calculateAvailableBalance(List<Balance> balanceList) {
        return balanceList.stream()
                .mapToDouble(Balance::getQuota)
                .sum();
    }

    private Session createSession(AccountingRequestDto request) {
        return new Session(
                request.sessionId(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                0,
                0L,
                request.framedIPAddress(),
                request.nasIP(),
                request.nasPortId(),
                0,
                0,null,
                request.username(),
                null,
                null,
                null,
                0
        );
    }

    private boolean hasMatchingNasPortId(List<Session> userSessions, String requestNasPortId,String userName) {
        if (userSessions == null || requestNasPortId == null) {
            return true;
        }

        for (Session ses : userSessions) {
            if (ses.getUserName().equals(userName) && requestNasPortId.equals(ses.getNasPortId())) {
                return false;
            }
        }
        return true;
    }

    private void generateAndSendCDR(AccountingRequestDto request, Session session, String serviceId, String bucketId) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildStartCDREvent, serviceId, bucketId);
    }
}
