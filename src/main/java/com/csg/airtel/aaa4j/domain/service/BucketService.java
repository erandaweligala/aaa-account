package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.response.ApiResponse;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.BalanceWrapper;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


@ApplicationScoped
public class BucketService {
    private static final Logger log = Logger.getLogger(BucketService.class);
    private final CacheClient cacheClient;
    private final COAService  coaService;

    public BucketService(CacheClient cacheClient, COAService coaService) {
        this.cacheClient = cacheClient;
        this.coaService = coaService;
    }

    /**
     * Remove expired balances based on serviceExpiry or bucketExpiryDate.
     * A balance is considered expired if either serviceExpiry or bucketExpiryDate is before the current time.
     *
     * @param balances list of balances to filter
     * @return filtered list containing only non-expired balances
     */
    private List<Balance> removeExpiredBalances(List<Balance> balances) {
        if (balances == null || balances.isEmpty()) {
            return balances;
        }

        LocalDateTime now = LocalDateTime.now();
        List<Balance> nonExpiredBalances = new ArrayList<>();

        for (Balance balance : balances) {
            boolean isExpired = false;

            // Check serviceExpiry
            if (balance.getServiceExpiry() != null && balance.getServiceExpiry().isBefore(now)) {
                isExpired = true;
                log.infof("Removing expired balance: bucketId=%s, serviceExpiry=%s",
                        balance.getBucketId(), balance.getServiceExpiry());
            }

            // Check bucketExpiryDate
            if (!isExpired && balance.getBucketExpiryDate() != null && balance.getBucketExpiryDate().isBefore(now)) {
                isExpired = true;
                log.infof("Removing expired balance: bucketId=%s, bucketExpiryDate=%s",
                        balance.getBucketId(), balance.getBucketExpiryDate());
            }

            if (!isExpired) {
                nonExpiredBalances.add(balance);
            }
        }

        return nonExpiredBalances;
    }

    public Uni<ApiResponse<Balance>> addBucketBalance(String userName, BalanceWrapper balance) {
        // Input validation
        if (userName == null || userName.isBlank()) {
            return Uni.createFrom().item(createErrorResponse("Username is required"));
        }
        if (balance == null) {
            return Uni.createFrom().item(createErrorResponse("Balance is required"));
        }


        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    UserSessionData updatedUserData;

                    if (userData == null ) {
                        // Create new entry without session section, only with balance details
                        log.infof("User data not found for user %s, creating new entry with balance", userName);
                        List<Balance> newBalances = new ArrayList<>();
                        newBalances.add(balance.getBalance());
                        String groupId = null;
                        if(balance.getBalance().isGroup()){
                            groupId = balance.getBalance().getBucketUsername();
                        }

                        updatedUserData = UserSessionData.builder()
                                .concurrency(balance.getConcurrency())
                                .groupId(groupId)
                                .userName(userName)
                                .balance(Collections.unmodifiableList(newBalances))
                                .sessions(Collections.emptyList())
                                .build();
                    } else {
                        // Create defensive copy with null-safe handling for existing user
                        List<Balance> existingBalances = Objects.requireNonNullElse(userData.getBalance(), List.of());

                        // Remove expired balances before adding new one
                        List<Balance> nonExpiredBalances = removeExpiredBalances(existingBalances);

                        List<Balance> newBalances = new ArrayList<>(nonExpiredBalances);
                        newBalances.add(balance.getBalance());

                        // Use immutable builder/wither pattern
                        updatedUserData = userData.toBuilder()
                                .balance(Collections.unmodifiableList(newBalances))
                                .build();
                    }

                    return cacheClient.updateUserAndRelatedCaches(userName, updatedUserData)
                            /*.call(() -> coaService.clearAllSessionsAndSendCOA(userData,userName)) no need to disconnect*/
                            .onItem().transform(result -> createSuccessResponse(balance.getBalance(),"Bucket Added Successfully"));
                })
                .onFailure().recoverWithItem(throwable -> {
                    log.errorf("Failed to add balance for user {}: {}",
                            userName, throwable.getMessage(), throwable);
                    return createErrorResponse(
                            "Failed to add balance: " + throwable.getMessage()
                    );
                });
    }


    public Uni<ApiResponse<Balance>> updateBucketBalance(String userName, Balance balance, String serviceId) {
        log.infof("Updating bucket Balance for user %s", userName);
        // Input validation
        if (userName == null || userName.isBlank()) {
            return Uni.createFrom().item(createErrorResponse("Username is required"));
        }
        if (balance == null) {
            return Uni.createFrom().item(createErrorResponse("Balance is required"));
        }
        if (serviceId == null || serviceId.isBlank()) {
            return Uni.createFrom().item(createErrorResponse("Service Id is required"));
        }

        if (balance.getServiceId() == null || !balance.getServiceId().equals(serviceId)) {
            return Uni.createFrom().item(createErrorResponse("Balance serviceId must match the provided serviceId"));
        }

        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        return Uni.createFrom().item(createErrorResponse("User not found"));
                    }

                    List<Balance> existingBalances = userData.getBalance() != null
                            ? new ArrayList<>(userData.getBalance())
                            : new ArrayList<>();

                    // Remove expired balances
                    List<Balance> balanceList = removeExpiredBalances(existingBalances);

                    balanceList.removeIf(b -> b.getServiceId().equals(serviceId));

                    balanceList.add(balance);

                    UserSessionData updatedUserData = userData.toBuilder()
                            .balance(Collections.unmodifiableList(balanceList))
                            .build();

                    return cacheClient.updateUserAndRelatedCaches(userName, updatedUserData)
                            .onItem().transform(result -> {
                                log.infof("Successfully updated balance for user %s, serviceId %s",
                                        userName, serviceId);
                                return createSuccessResponse(balance,"Updated balance Successfully");
                            });
                })
                .onFailure().recoverWithItem(throwable -> {
                    log.errorf("Failed to update balance for user %s: %s",
                            userName, throwable.getMessage(), throwable);
                    return createErrorResponse(
                            "Failed to update balance: " + throwable.getMessage()
                    );
                });
    }


    private ApiResponse<Balance> createSuccessResponse(Balance balance,String massage) {
        ApiResponse<Balance> response = new ApiResponse<>();
        response.setTimestamp(Instant.now());
        response.setMessage(massage);
        response.setStatus(Response.Status.OK);
        response.setData(balance);
        return response;
    }

    private ApiResponse<Balance> createErrorResponse(String message) {
        ApiResponse<Balance> response = new ApiResponse<>();
        response.setTimestamp(Instant.now());
        response.setMessage(message);
        response.setData(null);
        response.setStatus(Response.Status.BAD_REQUEST);
        return response;
    }


    public Uni<ApiResponse<Balance>> terminateSessions(String userName,String sessionId) {
        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        return Uni.createFrom().item(createErrorResponse("User not found"));
                    }
                   return coaService.clearAllSessionsAndSendCOA(userData,userName,sessionId)
                           .invoke(() -> userData.getSessions().clear())
                           .onItem().transform(result -> {
                               log.infof("Sessions Terminated successfully for user %s",
                                       userName);
                               return createSuccessResponse(null,"Terminated successfully");
                           });

                })
                .onFailure().recoverWithItem(throwable -> {
                    log.errorf("Failed to send Disconnection COA for user %s: %s",
                            userName, throwable.getMessage(), throwable);
                    return createErrorResponse(
                            "Failed to send Disconnection COA: " + throwable.getMessage()
                    );
                });
    }


}
