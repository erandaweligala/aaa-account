package com.csg.airtel.aaa4j.external.repository;


import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;


import java.util.ArrayList;
import java.util.List;

import static com.csg.airtel.aaa4j.domain.constant.SQLConstant.QUERY_BALANCE;

@ApplicationScoped
public class UserBucketRepository {
    private static final Logger log = Logger.getLogger(UserBucketRepository.class);

    // Column name constants to avoid repeated string allocations
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

    // Default initial capacity for typical bucket list size (reduces ArrayList resizing)
    private static final int DEFAULT_BUCKET_LIST_CAPACITY = 10;

    final Pool client;

    @Inject
    public UserBucketRepository(Pool client) {
        this.client = client;
    }

    /**
     * Fetches service buckets for a user from database.
     * Optimized for high throughput (500+ TPS) with:
     * - Minimal logging (debug/trace levels only)
     * - Pre-sized collections to avoid resizing
     * - Constant string reuse
     * - Reactive non-blocking execution
     *
     * @param userName the username to fetch buckets for
     * @return Uni containing list of ServiceBucketInfo
     */
    public Uni<List<ServiceBucketInfo>> getServiceBucketsByUserName(String userName) {
        // Use trace level for high-frequency operations to reduce logging overhead
        if (log.isTraceEnabled()) {
            log.tracef("Fetching service buckets for user: %s", userName);
        }

        return client
                .preparedQuery(QUERY_BALANCE)
                .execute(Tuple.of(userName))
                .onItem().transform(this::mapRowsToServiceBuckets)
                .onFailure().invoke(error -> {
                    // Keep error logging but make it conditional for better performance
                    if (log.isDebugEnabled()) {
                        log.debugf(error, "Error fetching service buckets for user: %s", userName);
                    }
                })
                .onItem().invoke(results -> {
                    // Use trace level for success cases in high-throughput scenarios
                    if (log.isTraceEnabled()) {
                        log.tracef("Fetched %d service buckets for user: %s", results.size(), userName);
                    }
                });
    }

    /**
     * Maps database rows to ServiceBucketInfo objects.
     * Optimized with:
     * - Pre-sized ArrayList based on row count
     * - Column name constants to avoid string allocation
     * - Single-pass iteration
     *
     * @param rows the database result set
     * @return list of mapped ServiceBucketInfo objects
     */
    private List<ServiceBucketInfo> mapRowsToServiceBuckets(RowSet<Row> rows) {
        // Pre-size list based on actual row count to prevent ArrayList resizing
        int rowCount = rows.size();
        List<ServiceBucketInfo> results = new ArrayList<>(rowCount > 0 ? rowCount : DEFAULT_BUCKET_LIST_CAPACITY);

        for (Row row : rows) {
            ServiceBucketInfo info = new ServiceBucketInfo();

            // Use constant column names to avoid repeated string allocations
            info.setBucketId(row.getLong(COL_BUCKET_ID));
            info.setCurrentBalance(row.getLong(COL_CURRENT_BALANCE));
            info.setServiceId(row.getLong(COL_ID));
            info.setRule(row.getString(COL_RULE));
            info.setPriority(row.getLong(COL_PRIORITY));
            info.setInitialBalance(row.getLong(COL_INITIAL_BALANCE));
            info.setStatus(row.getString(COL_STATUS));
            info.setUsage(row.getLong(COL_USAGE));
            info.setExpiryDate(row.getLocalDateTime(COL_EXPIRY_DATE));
            info.setServiceStartDate(row.getLocalDateTime(COL_SERVICE_START_DATE));
            info.setPlanId(row.getString(COL_PLAN_ID));
            info.setBucketUser(row.getString(COL_BUCKET_USER));
            info.setConsumptionLimit(row.getLong(COL_CONSUMPTION_LIMIT));
            info.setConsumptionTimeWindow(row.getLong(COL_CONSUMPTION_LIMIT_WINDOW));
            info.setSessionTimeout(row.getString(COL_SESSION_TIMEOUT));
            info.setTimeWindow(row.getString(COL_TIME_WINDOW));
            info.setBucketExpiryDate(row.getLocalDateTime(COL_EXPIRATION));

            results.add(info);
        }
        return results;
    }

}
