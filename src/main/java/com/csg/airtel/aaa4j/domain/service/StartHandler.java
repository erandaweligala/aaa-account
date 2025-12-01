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
public class StartHandler {
    private static final Logger log = Logger.getLogger(StartHandler.class);
    private final CacheClient utilCache;
    private final UserBucketRepository userRepository;
    private final AccountProducer  accountProducer;

    @Inject
    public StartHandler(CacheClient utilCache, UserBucketRepository userRepository, AccountProducer accountProducer) {
        this.utilCache = utilCache;
        this.userRepository = userRepository;
        this.accountProducer = accountProducer;
    }

    public Uni<Void> processAccountingStart(AccountingRequestDto request,String traceId) {
   // todo implement get highest priority bucket and check  availabe balances if have balances highest balance is group , need add session for groupSessionData level
        long startTime = System.currentTimeMillis();
        log.infof("[traceId: %s] Processing accounting start for user: %s, sessionId: %s",
                traceId, request.username(), request.sessionId());

    return utilCache.getUserData(request.username())
            .onItem().invoke(userData ->
                    log.infof("[traceId: %s]User data retrieved for user: %s",traceId, request.username()))
            .onItem().transformToUni(userSessionData -> {
                if (userSessionData == null) {
                    log.infof("[traceId: %s] No cache entry found for user: %s", traceId,request.username());
                    Uni<Void> accountingResponseEventUni = handleNewUserSession(request);

                    long duration = System.currentTimeMillis() - startTime;
                    log.infof("[traceId: %s] Completed processing accounting start for user: %s in %d ms",
                            traceId, request.username(), duration);
                    return accountingResponseEventUni;
                } else {
                    log.infof("[traceId: %s] Existing session found for user: %s",traceId, request.username());
                    Uni<Void> accountingResponseEventUni = handleExistingUserSession(request, userSessionData);
                    long duration = System.currentTimeMillis() - startTime;
                    log.infof("[traceId: %s] Completed processing accounting start for user: %s in %d ms",
                            traceId, request.username(), duration);
                    return accountingResponseEventUni;
                }
            })
            .onFailure().recoverWithUni(throwable -> {
                log.errorf(throwable, "[traceId: %s] Error processing accounting start for user: %s", traceId, request.username());
                return Uni.createFrom().voidItem();
            });
}

    private Uni<Void> handleExistingUserSession(
            AccountingRequestDto request,
            UserSessionData userSessionData) {

        // Declare balanceListUni outside the if block
        Uni<List<Balance>> balanceListUni;

        String groupId = userSessionData.getGroupId();
        boolean isGroupUser = groupId != null && !groupId.equals("1");

        if (isGroupUser) {
            balanceListUni = utilCache.getUserData(groupId)
                    .onItem()
                    .transform(UserSessionData::getBalance);
        } else {
            // Use the user's own balance list if groupId is "1" or null
            balanceListUni = Uni.createFrom().item(userSessionData.getBalance());
        }

        // Chain the balance calculation to handle the asynchronous Uni
        return balanceListUni.onItem().transformToUni(balanceList -> {
            // Combine user's balance with the additional balance list
            List<Balance> combinedBalances = new ArrayList<>(userSessionData.getBalance());
            if (balanceList != null && !balanceList.isEmpty()) {
                combinedBalances.addAll(balanceList);
            }

            double availableBalance = calculateAvailableBalance(combinedBalances);

            if (availableBalance <= 0) {
                log.warnf("User: %s has exhausted their data balance. Cannot start new session.",
                        request.username());
                return accountProducer.produceAccountingResponseEvent(
                        MappingUtil.createResponse(request, "Data balance exhausted",
                                AccountingResponseEvent.EventType.COA,
                                AccountingResponseEvent.ResponseAction.DISCONNECT));
            }

            boolean sessionExists = userSessionData.getSessions()
                    .stream()
                    .anyMatch(session -> session.getSessionId().equals(request.sessionId()));

            if (sessionExists) {
                log.infof("[traceId: %s] Session already exists for user: %s, sessionId: %s",
                        request.username(), request.sessionId());
                return Uni.createFrom().voidItem();
            }

            // Add new session and update cache
            Session newSession = createSession(request);
            userSessionData.getSessions().add(newSession);

            return utilCache.updateUserAndRelatedCaches(request.username(), userSessionData)
                    .onItem().transformToUni(unused -> {
                        log.infof("[traceId: %s] New session added for user: %s, sessionId: %s",
                                request.username(), request.sessionId());
                        return Uni.createFrom().voidItem();
                    })
                    .invoke(() -> {
                        log.infof("cdr write event started for user: %s", request.username());
                        // Send CDR event asynchronously
                        generateAndSendCDR(request, newSession);
                    })
                    .onFailure().recoverWithUni(throwable -> {
                        log.errorf(throwable, "Failed to update cache for user: %s", request.username());
                        return Uni.createFrom().voidItem();
                    });
        });

    }


    private Uni<Void> handleNewUserSession(AccountingRequestDto request) {
        log.infof("No existing session data found for user: %s. Creating new session data.",
                request.username());

        return userRepository.getServiceBucketsByUserName(request.username())
                .onItem().transformToUni(serviceBuckets -> {
                    if (serviceBuckets == null || serviceBuckets.isEmpty()) {
                        log.warnf("No service buckets found for user: %s. Cannot create session data.",
                                request.username());
                        return accountProducer.produceAccountingResponseEvent(
                                        MappingUtil.createResponse(request, "No service buckets found",
                                                AccountingResponseEvent.EventType.COA,
                                                AccountingResponseEvent.ResponseAction.DISCONNECT))
                                .replaceWithVoid();
                    }

                    double totalQuota = 0.0;
                    List<Balance> balanceList = new ArrayList<>(serviceBuckets.size());
                    List<Balance> balanceGroupList = new ArrayList<>();
                    String groupId = null;

                    for (ServiceBucketInfo bucket : serviceBuckets) {
                        Balance balance = MappingUtil.createBalance(bucket);

                        groupId = getGroupId(request, bucket, balanceGroupList, balance, groupId, balanceList);
                        totalQuota += bucket.getCurrentBalance();
                    }

                    // Check quota early to fail fast
                    if (totalQuota <= 0) {
                        log.warnf("User: %s has zero total data quota. Cannot create session data.",
                                request.username());
                        return accountProducer.produceAccountingResponseEvent(
                                        MappingUtil.createResponse(request, "Data quota is zero",
                                                AccountingResponseEvent.EventType.COA,
                                                AccountingResponseEvent.ResponseAction.DISCONNECT))
                                .replaceWithVoid();
                    }


                    UserSessionData newUserSessionData = new UserSessionData();
                    newUserSessionData.setGroupId(groupId);
                    newUserSessionData.setUserName(request.username());
                    Session session = createSession(request);
                    newUserSessionData.setSessions(new ArrayList<>(List.of(session)));
                    newUserSessionData.setBalance(balanceList);


                    Uni<Void> userStorageUni = utilCache.storeUserData(request.username(), newUserSessionData)
                            .onItem().invoke(unused ->
                                    log.infof("New user session data created and stored for user: %s", request.username()))
                            .replaceWithVoid();


                    if (!balanceGroupList.isEmpty()) {
                        UserSessionData groupSessionData = new UserSessionData();
                        groupSessionData.setBalance(balanceGroupList);

                       final String finalGroupId = groupId;


                        Uni<Void> groupStorageUni = utilCache.getUserData(groupId)
                                .chain(existingData -> {
                                    if (existingData == null) {
                                        return utilCache.storeUserData(finalGroupId, groupSessionData)
                                                .onItem().invoke(unused -> log.infof("Group session data stored for groupId: %s", finalGroupId))
                                                .onFailure().invoke(failure -> log.errorf(failure, "Failed to store group data for groupId: %s", finalGroupId));
                                    } else {
                                        log.infof("Group session data already exists for groupId: %s", finalGroupId);
                                        return Uni.createFrom().voidItem();
                                    }
                                });

                        // Execute both storage operations in parallel
                        userStorageUni = Uni.combine().all().unis(userStorageUni, groupStorageUni)
                                .discardItems();
                    }

                    // Send CDR event asynchronously (fire and forget) after user storage
                    return userStorageUni.onItem().invoke(unused -> {
                        log.infof("CDR write event started for user: %s", request.username());
                        generateAndSendCDR(request, session);
                    });
                })
                .onFailure().recoverWithUni(throwable -> {
                    log.errorf(throwable, "Error creating new user session for user: %s",
                            request.username());
                    return Uni.createFrom().voidItem();
                });
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
                request.nasIP()
        );
    }

    private void generateAndSendCDR(AccountingRequestDto request, Session session) {
        CdrMappingUtil.generateAndSendCDR(request, session, accountProducer, CdrMappingUtil::buildStartCDREvent);
    }



}
