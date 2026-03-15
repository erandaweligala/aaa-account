package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.*;
import com.csg.airtel.aaa4j.domain.model.session.Balance;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class MappingUtil {

    public static final String USERNAME = "username";
    public static final String SESSION_ID = "sessionId";
    public static final String NAS_IP = "nasIP";
    public static final String FRAMED_IP = "framedIP";
    private static final Map<String, String> EMPTY_ATTRIBUTES = Collections.emptyMap();
    private static final String EMPTY = "";

    private MappingUtil() {
    }

    public static AccountingResponseEvent createResponse(
            AccountingRequestDto request,
            String message,
            AccountingResponseEvent.EventType eventType,
            AccountingResponseEvent.ResponseAction responseAction) {

        // Optimization: Only create the map if we actually have data
        Map<String, String> attributes = EMPTY_ATTRIBUTES;

        if (eventType == AccountingResponseEvent.EventType.COA) {
            attributes = Map.of(
                    USERNAME, request.username(),
                    SESSION_ID, request.sessionId(),
                    NAS_IP, request.nasIP(),
                    FRAMED_IP, request.framedIPAddress()
            );
        }

        return new AccountingResponseEvent(
                request.eventId(),
                request.username(),
                eventType,
                LocalDateTime.now(), // Consider passing this as a parameter if available
                request.sessionId(),
                responseAction,
                message,
                0L,
                attributes);
    }

    public static AccountingResponseEvent createResponse(
            String sessionId,
            String message,
            String nasIP,
            String framedIPAddress,
            String userName) {

        Objects.requireNonNull(sessionId, "sessionId null");
        Objects.requireNonNull(userName,  "userName null");

        Map<String, String> attributes = Map.of(
                USERNAME,   userName,
                SESSION_ID, sessionId,
                NAS_IP,     nasIP    != null ? nasIP    : EMPTY,
                FRAMED_IP,  framedIPAddress != null ? framedIPAddress : EMPTY
        );

        return new AccountingResponseEvent(
                sessionId,
                userName,
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                sessionId,
                AccountingResponseEvent.ResponseAction.DISCONNECT,
                message != null ? message : EMPTY,
                0L,
                attributes);
    }

    public static Balance createBalance(ServiceBucketInfo bucket) {
        Balance balance = new Balance();
        balance.setBucketId(String.valueOf(bucket.getBucketId()));
        balance.setServiceExpiry(bucket.getExpiryDate());
        balance.setPriority(bucket.getPriority());
        balance.setQuota(bucket.getCurrentBalance());
        balance.setInitialBalance(bucket.getInitialBalance());
        balance.setServiceStartDate(bucket.getServiceStartDate());
        balance.setServiceId(String.valueOf(bucket.getServiceId()));
        balance.setServiceStatus(bucket.getStatus());
        balance.setConsumptionLimit(bucket.getConsumptionLimit());
        balance.setTimeWindow(bucket.getTimeWindow());
        balance.setConsumptionLimitWindow(bucket.getConsumptionTimeWindow());
        balance.setBucketUsername(bucket.getBucketUser());
        balance.setBucketExpiryDate(bucket.getBucketExpiryDate());
        balance.setGroup(bucket.isGroup());
        balance.setUnlimited(bucket.isUnlimited());
        balance.setUsage(bucket.getUsage());
        return balance;
    }

    /**
     * Creates a DBWriteRequest object for updating bucket balance information.
     *
     * @param balance the balance object containing the bucket data to write
     * @param userName the username associated with the request
     * @param sessionId the session ID for tracking purposes
     * @param eventType the type of database event (CREATE_EVENT or UPDATE_EVENT)
     * @return a fully populated DBWriteRequest object ready to be sent to the database writer
     */
    public static DBWriteRequest createDBWriteRequest(
            Balance balance,
            String userName,
            String sessionId,
            EventType eventType) {
        long usage = 0;
        long currentBalance;
        if(!balance.isUnlimited()) {
            usage = balance.getInitialBalance() - balance.getQuota();
            currentBalance = balance.getQuota();

        }else {
            usage =balance.getUsage();
            currentBalance = balance.getInitialBalance();
        }

        DBWriteRequest dbWriteRequest = new DBWriteRequest();
        dbWriteRequest.setSessionId(sessionId);
        dbWriteRequest.setUserName(userName);
        dbWriteRequest.setEventType(eventType);
        dbWriteRequest.setTableName(AppConstant.BUCKET_INSTANCE_TABLE);
        dbWriteRequest.setEventId(UUID.randomUUID().toString());
        dbWriteRequest.setTimestamp(LocalDateTime.now());

        // Set WHERE conditions for identifying the record
        Map<String, Object> whereConditions = Map.of(
                AppConstant.SERVICE_ID, balance.getServiceId(),
                AppConstant.ID, balance.getBucketId()
        );
        dbWriteRequest.setWhereConditions(whereConditions);

        // Set column values to update
        Map<String, Object> columnValues = Map.of(
                AppConstant.CURRENT_BALANCE, currentBalance,
                AppConstant.USAGE, usage,
                AppConstant.UPDATED_AT, LocalDateTime.now()
        );
        dbWriteRequest.setColumnValues(columnValues);

        return dbWriteRequest;
    }

}
