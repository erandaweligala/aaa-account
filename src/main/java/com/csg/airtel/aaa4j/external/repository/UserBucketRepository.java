package com.csg.airtel.aaa4j.external.repository;


import com.csg.airtel.aaa4j.domain.model.ExpiringBucketInfo;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.csg.airtel.aaa4j.domain.constant.SQLConstant.QUERY_BALANCE;
import static com.csg.airtel.aaa4j.domain.constant.SQLConstant.QUERY_BUCKETS_EXPIRING_SOON;

@ApplicationScoped
public class UserBucketRepository {

    private static final Logger log = Logger.getLogger(UserBucketRepository.class);

    private static final String COL_BUCKET_ID = "BUCKET_ID";
    private static final String COL_CURRENT_BALANCE = "CURRENT_BALANCE";
    private static final String COL_ID = "ID";
    private static final String COL_RULE = "RULE";
    private static final String COL_PRIORITY = "PRIORITY";
    private static final String COL_INITIAL_BALANCE = "INITIAL_BALANCE";
    private static final String COL_STATUS = "STATUS";
    private static final String COL_USAGE = "USAGE";
    private static final String COL_EXPIRY_DATE = "EXPIRY_DATE";
    private static final String COL_SERVICE_START_DATE = "SERVICE_START_DATE";
    private static final String COL_PLAN_ID = "PLAN_ID";
    private static final String COL_BUCKET_USER = "BUCKET_USER";
    private static final String COL_CONSUMPTION_LIMIT = "CONSUMPTION_LIMIT";
    private static final String COL_CONSUMPTION_LIMIT_WINDOW = "CONSUMPTION_LIMIT_WINDOW";
    private static final String COL_SESSION_TIMEOUT = "SESSION_TIMEOUT";
    private static final String COL_TIME_WINDOW = "TIME_WINDOW";
    private static final String COL_EXPIRATION = "EXPIRATION";

    private static final int DEFAULT_BUCKET_LIST_CAPACITY = 10;
    public static final String IS_UNLIMITED = "IS_UNLIMITED";
    public static final String IS_GROUP = "IS_GROUP";
    public static final String CONCURRENCY = "CONCURRENCY";
    public static final String NOTIFICATION_TEMPLATES = "NOTIFICATION_TEMPLATES";

    final Pool client;

    @Inject
    public UserBucketRepository(Pool client) {
        this.client = client;
    }

    /**
     * Fetches service buckets for a given user.
     *
     * @param userName the username to fetch buckets for
     * @return Uni containing list of ServiceBucketInfo
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 10000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 2,
            delay = 100,
            maxDuration = 10000
    )
    public Uni<List<ServiceBucketInfo>> getServiceBucketsByUserName(String userName) {
        if (log.isDebugEnabled()) {
            log.debugf("Fetching service buckets for user: %s", userName);
        }
        return client
                .preparedQuery(QUERY_BALANCE)
                .execute(Tuple.of(userName))
                    .onItem().transform(this::mapRowsToServiceBuckets)
                .onFailure().invoke(error -> {
                    if (log.isDebugEnabled()) {
                        log.debugf(error, "Error fetching service buckets for user: %s", userName);
                    }
                })
                .onItem().invoke(results -> {
                    if (log.isDebugEnabled()) {
                        log.debugf("Fetched %d service buckets for user: %s", results.size(), userName);
                    }
                });
    }

    /**
     * Maps database rows to ServiceBucketInfo objects.
     *
     * @param rows the database result set
     * @return list of mapped ServiceBucketInfo objects
     */
    private List<ServiceBucketInfo> mapRowsToServiceBuckets(RowSet<Row> rows) {
        int rowCount = rows.size();
        List<ServiceBucketInfo> results = new ArrayList<>(rowCount > 0 ? rowCount : DEFAULT_BUCKET_LIST_CAPACITY);

        for (Row row : rows) {
            ServiceBucketInfo info = new ServiceBucketInfo();

            // Service instance identifiers
            info.setServiceId(row.getLong(COL_ID));
            info.setBucketId(row.getLong(COL_BUCKET_ID));
            info.setBucketUser(row.getString(COL_BUCKET_USER));
            info.setPlanId(row.getString(COL_PLAN_ID));
            info.setStatus(row.getString(COL_STATUS));

            // Service dates
            info.setServiceStartDate(row.getLocalDateTime(COL_SERVICE_START_DATE));
            info.setExpiryDate(row.getLocalDateTime(COL_EXPIRY_DATE));

            // Bucket configuration
            info.setRule(row.getString(COL_RULE));
            info.setPriority(row.getLong(COL_PRIORITY));
            info.setInitialBalance(row.getLong(COL_INITIAL_BALANCE) != null ? row.getLong(COL_INITIAL_BALANCE) : 0);
            info.setCurrentBalance(row.getLong(COL_CURRENT_BALANCE) != null ? row.getLong(COL_CURRENT_BALANCE) : 0);
            info.setUsage(row.getLong(COL_USAGE));

            // Consumption limits and windows
            info.setConsumptionLimit(row.getLong(COL_CONSUMPTION_LIMIT) != null ? row.getLong(COL_CONSUMPTION_LIMIT) : 0);
            info.setConsumptionTimeWindow(row.getLong(COL_CONSUMPTION_LIMIT_WINDOW)  != null ? row.getLong(COL_CONSUMPTION_LIMIT_WINDOW) : 0);
            info.setTimeWindow(row.getString(COL_TIME_WINDOW));
            info.setBucketExpiryDate(row.getLocalDateTime(COL_EXPIRATION));
            info.setUnlimited(row.getLong(IS_UNLIMITED) == 1);
            info.setGroup(row.getLong(IS_GROUP) == 1);
            info.setConcurrency(row.getLong(CONCURRENCY));


            // Session configuration
            info.setSessionTimeout(row.getString(COL_SESSION_TIMEOUT));
            info.setNotificationTemplates(row.getString(NOTIFICATION_TEMPLATES));

            results.add(info);
        }
        return results;
    }

    /**
     * Fetches buckets that are expiring within the specified number of days.
     *
     * @param maxDaysToExpire maximum number of days in the future to check for expiration
     * @return Uni containing list of ExpiringBucketInfo
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 10000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 2,
            delay = 100,
            maxDuration = 10000
    )
    public Uni<List<ExpiringBucketInfo>> getBucketsExpiringSoon(int maxDaysToExpire) {
        if (log.isDebugEnabled()) {
            log.debugf("Fetching buckets expiring within %d days", maxDaysToExpire);
        }
        return client
                .preparedQuery(QUERY_BUCKETS_EXPIRING_SOON)
                .execute(Tuple.of(maxDaysToExpire))
                .onItem().transform(this::mapRowsToExpiringBuckets)
                .onFailure().invoke(error ->
                        log.errorf(error, "Error fetching buckets expiring within %d days", maxDaysToExpire))
                .onItem().invoke(results -> {
                    if (log.isDebugEnabled()) {
                        log.debugf("Fetched %d buckets expiring within %d days", results.size(), maxDaysToExpire);
                    }
                });
    }

    /**
     * Maps database rows to ExpiringBucketInfo records.
     *
     * @param rows the database result set
     * @return list of ExpiringBucketInfo records
     */
    private List<ExpiringBucketInfo> mapRowsToExpiringBuckets(RowSet<Row> rows) {
        List<ExpiringBucketInfo> results = new ArrayList<>(rows.size());

        for (Row row : rows) {
            ExpiringBucketInfo info = new ExpiringBucketInfo(
                    row.getLong(COL_ID),
                    row.getLong(COL_BUCKET_ID),
                    row.getString(COL_BUCKET_USER),
                    row.getLocalDateTime(COL_EXPIRATION),
                    row.getLong(COL_INITIAL_BALANCE) != null ? row.getLong(COL_INITIAL_BALANCE) : 0L,
                    row.getLong(COL_CURRENT_BALANCE) != null ? row.getLong(COL_CURRENT_BALANCE) : 0L,
                    row.getString(NOTIFICATION_TEMPLATES)
            );
            results.add(info);
        }

        return results;
    }

}
