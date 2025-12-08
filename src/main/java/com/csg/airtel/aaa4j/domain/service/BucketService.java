package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.response.ApiResponse;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


@ApplicationScoped
public class BucketService {
    private static final Logger log = Logger.getLogger(BucketService.class);
    private final CacheClient cacheClient;
    private final COAService  coaService;

    @Inject
    public BucketService(CacheClient cacheClient, COAService coaService) {
        this.cacheClient = cacheClient;
        this.coaService = coaService;
    }

    public Uni<ApiResponse<Balance>> addBucketBalance(String userName, Balance balance) {
        // Input validation
        if (userName == null || userName.isBlank()) {
            return Uni.createFrom().item(createErrorResponse("Username is required"));
        }
        if (balance == null) {
            return Uni.createFrom().item(createErrorResponse("Balance is required"));
        }

        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    // Create defensive copy with null-safe handling
                    List<Balance> newBalances = new ArrayList<>(
                            Objects.requireNonNullElse(userData.getBalance(), List.of())
                    );
                    newBalances.add(balance);

                    // Use immutable builder/wither pattern
                    UserSessionData updatedUserData = userData.toBuilder()
                            .balance(Collections.unmodifiableList(newBalances))
                            .build();

                    return cacheClient.updateUserAndRelatedCaches(userName, updatedUserData)
                            /*.call(() -> coaService.clearAllSessionsAndSendCOA(userData,userName)) no need to disconnect*/
                            .onItem().transform(result -> createSuccessResponse(balance,"Bucket Added Successfully"));
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

                    List<Balance> balanceList = userData.getBalance() != null
                            ? new ArrayList<>(userData.getBalance())
                            : new ArrayList<>();

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


    public Uni<ApiResponse<Balance>> terminateSessions(String userName) {
        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        return Uni.createFrom().item(createErrorResponse("User not found"));
                    }
                   return coaService.clearAllSessionsAndSendCOA(userData,userName)
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
