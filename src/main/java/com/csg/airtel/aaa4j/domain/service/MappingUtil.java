package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.constant.AppConstant;
import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.DBWriteRequest;
import com.csg.airtel.aaa4j.domain.model.EventType;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.model.session.Balance;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public class MappingUtil {
    private MappingUtil() {
    }

    public static AccountingResponseEvent createResponse(
            AccountingRequestDto request,
            String message,
            AccountingResponseEvent.EventType eventType,
            AccountingResponseEvent.ResponseAction responseAction) {

        Map<String, String> attributes = eventType == AccountingResponseEvent.EventType.COA
                ? Map.of(
                "username", request.username(),
                "sessionId", request.sessionId(),
                "nasIP", request.nasIP(),
                "framedIP", request.framedIPAddress()
        )
                : Map.of();

        return new AccountingResponseEvent(
                eventType,
                LocalDateTime.now(),
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

        Map<String, String> attributes =
                 Map.of(
                "username", userName,
                "sessionId", sessionId,
                "nasIP", nasIP,
                "framedIP", framedIPAddress
        );

        return new AccountingResponseEvent(
                AccountingResponseEvent.EventType.COA,
                LocalDateTime.now(),
                sessionId,
                AccountingResponseEvent.ResponseAction.DISCONNECT,
                message,
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
                AppConstant.CURRENT_BALANCE, balance.getQuota(),
                AppConstant.USAGE, balance.getInitialBalance() - balance.getQuota(),
                AppConstant.UPDATED_AT, LocalDateTime.now()
        );
        dbWriteRequest.setColumnValues(columnValues);

        return dbWriteRequest;
    }

}
