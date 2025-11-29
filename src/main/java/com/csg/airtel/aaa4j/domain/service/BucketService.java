package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.response.ApiResponse;
import com.csg.airtel.aaa4j.domain.model.session.Balance;

import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;

import java.util.*;


@ApplicationScoped
public class BucketService {
    private static final Logger log = Logger.getLogger(BucketService.class);
    private final CacheClient cacheClient;

    public BucketService(CacheClient cacheClient) {
        this.cacheClient = cacheClient;
    }

    public Uni<ApiResponse<Balance>> addBucketBalance(String userName, Balance balance) {
        Uni<ApiResponse<Balance>> validationResult = validateBalanceInput(userName, balance, null);
        if (validationResult != null) {
            return validationResult;
        }

        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    List<Balance> newBalances = new ArrayList<>(
                            Objects.requireNonNullElse(userData.getBalance(), List.of())
                    );
                    newBalances.add(balance);

                    UserSessionData updatedUserData = userData.toBuilder()
                            .balance(Collections.unmodifiableList(newBalances))
                            .build();

                    return updateCacheAndCreateResponse(userName, updatedUserData, balance, "add balance");
                });
    }


    public Uni<ApiResponse<Balance>> updateBucketBalance(String userName, Balance balance, String serviceId) {
        log.infof("Updating bucket Balance for user %s", userName);
        Uni<ApiResponse<Balance>> validationResult = validateBalanceInput(userName, balance, serviceId);
        if (validationResult != null) {
            return validationResult;
        }

        if (balance.getServiceId() == null || !balance.getServiceId().equals(serviceId)) {
            return Uni.createFrom().item(createErrorResponse("Balance serviceId must match the provided serviceId"));
        }

        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        return Uni.createFrom().item(createErrorResponse("User not found"));
                    }

                    List<Balance> balanceList = userData.getBalance() != null
                            ? new ArrayList<>(userData.getBalance())
                            : new ArrayList<>();

                    balanceList.removeIf(b -> b.getServiceId().equals(serviceId));
                    balanceList.add(balance);

                    UserSessionData updatedUserData = userData.toBuilder()
                            .balance(Collections.unmodifiableList(balanceList))
                            .build();

                    return updateCacheAndCreateResponse(userName, updatedUserData, balance, "update balance");
                });
    }

    private Uni<ApiResponse<Balance>> validateBalanceInput(String userName, Balance balance, String serviceId) {
        if (userName == null || userName.isBlank()) {
            return Uni.createFrom().item(createErrorResponse("Username is required"));
        }
        if (balance == null) {
            return Uni.createFrom().item(createErrorResponse("Balance is required"));
        }
        if (serviceId != null && (serviceId.isBlank())) {
            return Uni.createFrom().item(createErrorResponse("Service Id is required"));
        }
        return null;
    }

    private Uni<ApiResponse<Balance>> updateCacheAndCreateResponse(
            String userName,
            UserSessionData updatedUserData,
            Balance balance,
            String operation) {
        return cacheClient.updateUserAndRelatedCaches(userName, updatedUserData)
                .onItem().transform(result -> {
                    log.infof("Successfully performed %s for user %s", operation, userName);
                    return createSuccessResponse(balance);
                })
                .onFailure().recoverWithItem(throwable -> {
                    log.errorf("Failed to %s for user %s: %s",
                            operation, userName, throwable.getMessage(), throwable);
                    return createErrorResponse("Failed to " + operation + ": " + throwable.getMessage());
                });
    }


    private ApiResponse<Balance> createSuccessResponse(Balance balance) {
        ApiResponse<Balance> response = new ApiResponse<>();
        response.setTimestamp(Instant.now());
        response.setMessage("Balance added successfully");
        response.setData(balance);
        return response;
    }

    private ApiResponse<Balance> createErrorResponse(String message) {
        ApiResponse<Balance> response = new ApiResponse<>();
        response.setTimestamp(Instant.now());
        response.setMessage(message);
        response.setData(null);
        return response;
    }






}
