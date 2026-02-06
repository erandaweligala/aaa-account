package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
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
    public static final String USER_NOT_FOUND = "User not found";
    public static final String USERNAME_IS_REQUIRED = "Username is required";
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
                LoggingUtil.logInfo(log, CLASS_NAME, "removeExpiredBalances", "Removing expired balance: bucketId=%s, serviceExpiry=%s",
                        balance.getBucketId(), balance.getServiceExpiry());
            }

            // Check bucketExpiryDate
            if (!isExpired && balance.getBucketExpiryDate() != null && balance.getBucketExpiryDate().isBefore(now)) {
                isExpired = true;
                LoggingUtil.logInfo(log, CLASS_NAME, "removeExpiredBalances", "Removing expired balance: bucketId=%s, bucketExpiryDate=%s",
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
            return Uni.createFrom().item(createErrorResponse(USERNAME_IS_REQUIRED));
        }
        if (balance == null) {
            return Uni.createFrom().item(createErrorResponse("Balance is required"));
        }


        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    UserSessionData updatedUserData;

                    if (userData == null ) {
                        // Create new entry without session section, only with balance details
                        LoggingUtil.logInfo(log, CLASS_NAME, "addBucketBalance", "User data not found for user %s, creating new entry with balance", userName);
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

                    return cacheClient.updateUserAndRelatedCaches(userName, updatedUserData,userName)
                            /*.call(() -> coaService.clearAllSessionsAndSendCOA(userData,userName)) no need to disconnect*/
                            .onItem().transform(result -> createSuccessResponse(balance.getBalance(),"Bucket Added Successfully"));
                })
                .onFailure().recoverWithItem(throwable -> {
                    LoggingUtil.logError(log, CLASS_NAME, "addBucketBalance", throwable, "Failed to add balance for user {}: {}",
                            userName, throwable.getMessage());
                    return createErrorResponse(
                            "Failed to add balance: " + throwable.getMessage()
                    );
                });
    }


    public Uni<ApiResponse<Balance>> updateBucketBalance(String userName, Balance balance, String serviceId) {
        LoggingUtil.logInfo(log, CLASS_NAME, "updateBucketBalance", "Updating bucket Balance for user %s", userName);
        // Input validation
        if (userName == null || userName.isBlank()) {
            return Uni.createFrom().item(createErrorResponse(USERNAME_IS_REQUIRED));
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
                        return Uni.createFrom().item(createErrorResponse(USER_NOT_FOUND));
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

                    return cacheClient.updateUserAndRelatedCaches(userName, updatedUserData,userName)
                            .onItem().transform(result -> {
                                LoggingUtil.logInfo(log, CLASS_NAME, "updateBucketBalance", "Successfully updated balance for user %s, serviceId %s",
                                        userName, serviceId);
                                return createSuccessResponse(balance,"Updated balance Successfully");
                            });
                })
                .onFailure().recoverWithItem(throwable -> {
                    LoggingUtil.logError(log, CLASS_NAME, "updateBucketBalance", throwable, "Failed to update balance for user %s: %s",
                            userName, throwable.getMessage());
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
                        return Uni.createFrom().item(createErrorResponse(USER_NOT_FOUND));
                    }
                   return coaService.clearAllSessionsAndSendCOA(userData,userName,sessionId)
                           .onItem().transform(updatedUserData -> {
                               LoggingUtil.logInfo(log, CLASS_NAME, "terminateSessions", "Sessions Terminated successfully for user %s, updated session count: %d",
                                       userName, updatedUserData != null && updatedUserData.getSessions() != null ?
                                       updatedUserData.getSessions().size() : 0);
                               return createSuccessResponse(null,"Terminated successfully");
                           });

                })
                .onFailure().recoverWithItem(throwable -> {
                    LoggingUtil.logError(log, CLASS_NAME, "terminateSessions", throwable, "Failed to send Disconnection COA for user %s: %s",
                            userName, throwable.getMessage());
                    return createErrorResponse(
                            "Failed to send Disconnection COA: " + throwable.getMessage()
                    );
                });
    }

    /**
     * Terminate sessions using HTTP-based CoA disconnect (non-blocking, no overhead).
     * This method sends CoA disconnect via direct HTTP POST to NAS without Kafka overhead.
     * After receiving ACK, sessions are cleared from cache automatically.
     *
     * @param userName the username
     * @param sessionId specific session to disconnect (null for all sessions)
     * @return ApiResponse with operation result
     */
    public Uni<ApiResponse<Balance>> terminateSessionsViaHttp(String userName, String sessionId) {
        LoggingUtil.logInfo(log, CLASS_NAME, "terminateSessionsViaHttp", "Terminating sessions via HTTP for user %s, sessionId: %s", userName, sessionId);

        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        return Uni.createFrom().item(createErrorResponse(USER_NOT_FOUND));
                    }

                    // Send HTTP CoA disconnect (non-blocking, cache cleared after ACK)
                    return coaService.clearAllSessionsAndSendCOA(userData, userName, sessionId)
                            .onItem().transform(updatedUserData -> {
                                LoggingUtil.logInfo(log, CLASS_NAME, "terminateSessionsViaHttp", "HTTP CoA disconnect sent successfully for user %s, updated session count: %d",
                                        userName, updatedUserData != null && updatedUserData.getSessions() != null ?
                                        updatedUserData.getSessions().size() : 0);
                                return createSuccessResponse(null, "HTTP CoA disconnect sent successfully");
                            });
                })
                .onFailure().recoverWithItem(throwable -> {
                    LoggingUtil.logError(log, CLASS_NAME, "terminateSessionsViaHttp", throwable, "Failed to send HTTP CoA disconnect for user %s: %s",
                            userName, throwable.getMessage());
                    return createErrorResponse(
                            "Failed to send HTTP CoA disconnect: " + throwable.getMessage()
                    );
                });
    }




    public Uni<ApiResponse<String>> updateUserStatus(String userName, String status) {
        LoggingUtil.logInfo(log, CLASS_NAME, "updateUserStatus", "Updating user status for user %s to %s", userName, status);

        // Input validation
        if (userName == null || userName.isBlank()) {
            return Uni.createFrom().item(createErrorResponseString(USERNAME_IS_REQUIRED));
        }
        if (status == null || status.isBlank()) {
            return Uni.createFrom().item(createErrorResponseString("Status is required"));
        }

        // Validate status values
        if (!status.equalsIgnoreCase("ACTIVE") && !status.equalsIgnoreCase("BARRED")) {
            return Uni.createFrom().item(createErrorResponseString("Invalid status. Must be 'ACTIVE' or 'BARRED'"));
        }

        return cacheClient.getUserData(userName)
                .onItem().transformToUni(userData -> {
                    if (userData == null) {
                        return Uni.createFrom().item(createErrorResponseString(USER_NOT_FOUND));
                    }

                    String oldStatus = userData.getUserStatus();
                    LoggingUtil.logInfo(log, CLASS_NAME, "updateUserStatus", "Changing user status for user %s from %s to %s", userName, oldStatus, status);

                    // Update userStatus in UserSessionData
                    UserSessionData updatedUserData = userData.toBuilder()
                            .userStatus(status)
                            .build();

                    // Update cache and send COA for any status update
                    return cacheClient.updateUserAndRelatedCaches(userName, updatedUserData,userName)
                            .call(() -> {
                                // Send COA to notify NAS about status update for all active sessions
                                if (userData.getSessions() != null && !userData.getSessions().isEmpty()) {
                                    LoggingUtil.logInfo(log, CLASS_NAME, "updateUserStatus", "User status changed from %s to %s, sending COA to update %d active sessions for user %s",
                                            oldStatus, status, userData.getSessions().size(), userName);
                                    return coaService.clearAllSessionsAndSendCOA(updatedUserData, userName, null)
                                            .replaceWithVoid();
                                }
                                return Uni.createFrom().voidItem();
                            })
                            .onItem().transform(result -> {
                                LoggingUtil.logInfo(log, CLASS_NAME, "updateUserStatus", "Successfully updated user status for user %s to %s", userName, status);
                                return createSuccessResponseString(
                                        String.format("User status updated successfully from %s to %s", oldStatus, status)
                                );
                            });
                })
                .onFailure().recoverWithItem(throwable -> {
                    LoggingUtil.logError(log, CLASS_NAME, "updateUserStatus", throwable, "Failed to update user status for user %s: %s",
                            userName, throwable.getMessage());
                    return createErrorResponseString(
                            "Failed to update user status: " + throwable.getMessage()
                    );
                });
    }

    private ApiResponse<String> createSuccessResponseString(String message) {
        ApiResponse<String> response = new ApiResponse<>();
        response.setTimestamp(Instant.now());
        response.setMessage(message);
        response.setStatus(Response.Status.OK);
        response.setData(null);
        return response;
    }

    private ApiResponse<String> createErrorResponseString(String message) {
        ApiResponse<String> response = new ApiResponse<>();
        response.setTimestamp(Instant.now());
        response.setMessage(message);
        response.setData(null);
        response.setStatus(Response.Status.BAD_REQUEST);
        return response;
    }
}
