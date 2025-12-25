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
public class StartHandler {
    private static final StructuredLogger log = StructuredLogger.getLogger(StartHandler.class);
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
        long startTime = System.currentTimeMillis();

        // Set MDC context for correlation across all logs in this request
        StructuredLogger.setContext(traceId, request.username(), request.sessionId());
        StructuredLogger.setOperation("START");

        log.info("Processing accounting START request", StructuredLogger.Fields.create()
                .add("username", request.username())
                .add("sessionId", request.sessionId())
                .add("nasIP", request.nasIP())
                .add("framedIP", request.framedIPAddress())
                .build());

        return utilCache.getUserData(request.username())
                .onItem().invoke(userData -> {
                    if (log.isDebugEnabled()) {
                        log.debug("User data retrieved from cache", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .add("hasData", userData != null)
                                .build());
                    }
                })
                .onItem().transformToUni(userSessionData -> {
                    if (userSessionData == null) {
                        log.info("No cache entry found, creating new user session", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .build());
                        Uni<Void> accountingResponseEventUni = handleNewUserSession(request);

                        long duration = System.currentTimeMillis() - startTime;
                        log.info("Completed START processing for new user", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .addDuration(duration)
                                .addStatus("success")
                                .build());
                        return accountingResponseEventUni;
                    } else {
                        log.info("Existing session found, processing START", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .add("existingSessionCount", userSessionData.getSessions() != null ? userSessionData.getSessions().size() : 0)
                                .build());
                        Uni<Void> accountingResponseEventUni = handleExistingUserSession(request, userSessionData);
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("Completed START processing for existing user", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .addDuration(duration)
                                .addStatus("success")
                                .build());
                        return accountingResponseEventUni;
                    }
                })
                .onFailure().recoverWithUni(throwable -> {
                    long duration = System.currentTimeMillis() - startTime;

                    // Handle circuit breaker open specifically - service temporarily unavailable
                    if (throwable instanceof CircuitBreakerOpenException) {
                        log.error("Cache service circuit breaker OPEN", StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .addErrorCode("CIRCUIT_BREAKER_OPEN")
                                .addDuration(duration)
                                .addStatus("failed")
                                .add("reason", "Redis connectivity issues or high TPS")
                                .build());
                    } else {
                        log.error("Error processing accounting START", throwable, StructuredLogger.Fields.create()
                                .add("username", request.username())
                                .addErrorCode("START_PROCESSING_ERROR")
                                .addDuration(duration)
                                .addStatus("failed")
                                .add("errorType", throwable.getClass().getSimpleName())
                                .build());
                    }
                    return Uni.createFrom().voidItem();
                })
                .eventually(() -> StructuredLogger.clearContext()); // Clear MDC after request completes
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

        if(userSessionData.getConcurrency() <= userSessionData.getSessions().size()) {
            log.error("Maximum concurrency sessions exceeded", StructuredLogger.Fields.create()
                    .add("username", request.username())
                    .add("maxConcurrency", userSessionData.getConcurrency())
                    .add("currentSessions", userSessionData.getSessions().size())
                    .addErrorCode("CONCURRENCY_LIMIT_EXCEEDED")
                    .addStatus("rejected")
                    .build());
            return coaService.produceAccountingResponseEvent(
                    MappingUtil.createResponse(request, "Maximum number of concurrency sessions exceeded",
                            AccountingResponseEvent.EventType.COA,
                            AccountingResponseEvent.ResponseAction.DISCONNECT),
                    createSession(request),
                    request.username());
        }
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
            log.warn("Data balance exhausted", StructuredLogger.Fields.create()
                    .add("username", request.username())
                    .add("availableBalance", availableBalance)
                    .add("balanceCount", combinedBalances.size())
                    .addErrorCode("BALANCE_EXHAUSTED")
                    .addStatus("rejected")
                    .build());
            return coaService.produceAccountingResponseEvent(
                    MappingUtil.createResponse(request, "Data balance exhausted",
                            AccountingResponseEvent.EventType.COA,
                            AccountingResponseEvent.ResponseAction.DISCONNECT),
                    createSession(request),
                    request.username());
        }

        if (sessionAlreadyExists(userSessionData, request.sessionId())) {
            log.info("Session already exists, ignoring duplicate START", StructuredLogger.Fields.create()
                    .add("username", request.username())
                    .add("sessionId", request.sessionId())
                    .addStatus("duplicate")
                    .build());
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
            log.warnf("No valid balance found for user: %s. Cannot start new session.", request.username());
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
        if (!isGroupBalance) {
            userSessionData.getSessions().add(newSession);
        }

        return updateCachesForSession(request, userSessionData, newSession, isGroupBalance)
                .call(() -> sessionLifecycleManager.onSessionCreated(request.username(), newSession))
                .invoke(() -> {
                    log.infof("cdr write event started for user: %s", request.username());
                    generateAndSendCDR(request, newSession);
                })
                .onFailure().recoverWithUni(throwable -> {
                    log.errorf(throwable, "Failed to update cache for user: %s", request.username());
                    return Uni.createFrom().voidItem();
                });
    }

    private Session createSessionWithBalance(AccountingRequestDto request, Balance balance) {
        Session session = createSession(request);
        session.setPreviousUsageBucketId(balance.getBucketId());
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
        }

        return updateUserCacheOnly(request, userSessionData);
    }

    private Uni<Void> updateUserAndGroupCaches(
            AccountingRequestDto request,
            UserSessionData userSessionData,
            Session newSession,
            String groupId) {

        log.infof("Highest priority balance is a group balance. Adding session to group data for groupId: %s", groupId);

        return utilCache.getUserData(groupId)
                .onItem().transformToUni(groupSessionData -> {
                    if (groupSessionData != null) {
                        addSessionToGroupData(groupSessionData, newSession);
                        return updateBothCaches(request.username(), userSessionData, groupId, groupSessionData);
                    }

                    log.warnf("Group data not found for groupId: %s. Only updating user data.", groupId);
                    return utilCache.updateUserAndRelatedCaches(request.username(), userSessionData);
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
                        utilCache.updateUserAndRelatedCaches(username, userSessionData),
                        utilCache.updateUserAndRelatedCaches(groupId, groupSessionData)
                ).discardItems()
                .onItem().invoke(unused ->
                        log.infof("Session added to both user: %s and group: %s", username, groupId));
    }

    private Uni<Void> updateUserCacheOnly(AccountingRequestDto request, UserSessionData userSessionData) {
        return utilCache.updateUserAndRelatedCaches(request.username(), userSessionData)
                .onItem().invoke(unused ->
                        log.infof("New session added for user: %s, sessionId: %s",
                                request.username(), request.sessionId()))
                .replaceWithVoid();
    }


    private Uni<Void> handleNewUserSession(AccountingRequestDto request) {
        log.infof("No existing session data found for user: %s. Creating new session data.",
                request.username());

        return userRepository.getServiceBucketsByUserName(request.username())
                .onItem().transformToUni(serviceBuckets ->
                        processServiceBuckets(request, serviceBuckets))
                .onFailure().recoverWithUni(throwable -> {
                    log.errorf(throwable, "Error creating new user session for user: %s",
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
        log.warnf("No service buckets found for user: %s. Cannot create session data.", request.username());
        return coaService.produceAccountingResponseEvent(
                MappingUtil.createResponse(request, "No service buckets found",
                        AccountingResponseEvent.EventType.COA,
                        AccountingResponseEvent.ResponseAction.DISCONNECT),
                createSession(request),
                request.username());
    }

    private Uni<Void> handleZeroQuota(AccountingRequestDto request) {
        log.warnf("User: %s has zero total data quota. Cannot create session data.", request.username());
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
        String templates = null;
        long concurrency = 0;
        for (ServiceBucketInfo bucket : serviceBuckets) {
            Balance balance = MappingUtil.createBalance(bucket);
            groupId = getGroupId(request, bucket, balanceGroupList, balance, groupId, balanceList);
            concurrency = bucket.getConcurrency();
            templates = bucket.getNotificationTemplates();
            totalQuota += bucket.getCurrentBalance();
        }

        return new BucketProcessingResult(balanceList, balanceGroupList, groupId, totalQuota,concurrency,templates);
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
        UserSessionData newUserSessionData = buildUserSessionData(
                request,result.concurrency, result.balanceList(), result.groupId(), session, highestPriorityBalance,result.templates);

        Uni<Void> userStorageUni = storeUserSessionData(request.username(), newUserSessionData);

        if (!result.balanceGroupList().isEmpty()) {
            userStorageUni = storeUserAndGroupData(
                    request, result, session, highestPriorityBalance, userStorageUni);
        }

        final Session finalSession = session;
        return userStorageUni
                .call(() -> sessionLifecycleManager.onSessionCreated(request.username(), finalSession))
                .onItem().invoke(unused -> {
                    log.infof("CDR write event started for user: %s", request.username());
                    generateAndSendCDR(request, finalSession);
                });
    }

    private Uni<Void> handleNoValidBalance(AccountingRequestDto request) {
        log.warnf("No valid balance found for user: %s. Cannot create session data.", request.username());
        return coaService.produceAccountingResponseEvent(
                MappingUtil.createResponse(request, "No valid balance found",
                        AccountingResponseEvent.EventType.COA,
                        AccountingResponseEvent.ResponseAction.DISCONNECT),
                createSession(request),
                request.username());
    }

    private UserSessionData buildUserSessionData(
            AccountingRequestDto request,long concurrency,
            List<Balance> balanceList,
            String groupId,
            Session session,
            Balance highestPriorityBalance,String templates) {

        UserSessionData newUserSessionData = new UserSessionData();
        newUserSessionData.setGroupId(groupId);
        newUserSessionData.setUserName(request.username());
        newUserSessionData.setConcurrency(concurrency);
        newUserSessionData.setBalance(balanceList);
        newUserSessionData.setTemplateIds(templates);
        if (!isGroupBalance(highestPriorityBalance, request.username())) {
            newUserSessionData.setSessions(new ArrayList<>(List.of(session)));
        }

        return newUserSessionData;
    }

    private Uni<Void> storeUserSessionData(String username, UserSessionData sessionData) {
        return utilCache.storeUserData(username, sessionData)
                .onItem().invoke(unused ->
                        log.infof("New user session data created and stored for user: %s", username))
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

        if (isHighestPriorityGroupBalance) {
            groupSessionData.setSessions(new ArrayList<>(List.of(session)));
            log.infof("Adding session to new group data for groupId: %s (highest priority balance is group balance)", groupId);
        } else {
            groupSessionData.setSessions(new ArrayList<>());
        }

        return utilCache.storeUserData(groupId, groupSessionData)
                .onItem().invoke(unused -> log.infof("Group session data stored for groupId: %s", groupId))
                .onFailure().invoke(failure -> log.errorf(failure, "Failed to store group data for groupId: %s", groupId));
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
            log.infof("Adding session to existing group data for groupId: %s (highest priority balance is group balance)", groupId);
        }

        log.infof("Group session data already exists for groupId: %s", groupId);
        return utilCache.updateUserAndRelatedCaches(groupId, existingData)
                .onItem().invoke(unused -> log.infof("Existing group session data updated for groupId: %s", groupId));
    }

    private record BucketProcessingResult(
            List<Balance> balanceList,
            List<Balance> balanceGroupList,
            String groupId,
            double totalQuota,long concurrency,String templates) {
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
                null,
                0,
                0L,
                request.framedIPAddress(),
                request.nasIP(),
                request.nasPortId(),
                false,
                0,null
        );
    }

    private void generateAndSendCDR(AccountingRequestDto request, Session session) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildStartCDREvent);
    }



}
