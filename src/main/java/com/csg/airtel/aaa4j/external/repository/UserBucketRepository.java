package com.csg.airtel.aaa4j.external.repository;


import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import com.csg.airtel.aaa4j.domain.service.ExceptionMetricsService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.csg.airtel.aaa4j.domain.constant.SQLConstant.QUERY_BALANCE;

@ApplicationScoped
public class UserBucketRepository {

    private static final Logger log = Logger.getLogger(UserBucketRepository.class);
    private static final String M_QUERY = "getServiceBuckets";

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
    public static final String STATUS = "STATUS";
    public static final String TEMPLATE_ID = "TEMPLATE_ID";
    public static final String CYCLE_START_DATE = "CYCLE_START_DATE";
    public static final String RECURRING_FLAG = "RECURRING_FLAG";

    final Pool client;
    private final Timer accountingDbExecutionTimer;
    private final AtomicLong lastAccountingDbExecutionTimeMs = new AtomicLong(0);
    private final ExceptionMetricsService exceptionMetricsService;

    @Inject
    public UserBucketRepository(Pool client, MeterRegistry meterRegistry,
                                ExceptionMetricsService exceptionMetricsService) {
        this.client = client;
        this.exceptionMetricsService = exceptionMetricsService;
        this.accountingDbExecutionTimer = Timer.builder("radius.accounting.db.execution.time")
                .description("Time to complete a  DB execution time")
                .publishPercentiles(0.5, 0.75, 0.90, 0.95, 0.99)
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(10),
                        Duration.ofMillis(20),
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofMillis(1000),
                        Duration.ofMillis(2500),
                        Duration.ofMillis(5000))
                .register(meterRegistry);

        Gauge.builder("radius.accounting.db.execution.time.ms", lastAccountingDbExecutionTimeMs, AtomicLong::get)
                .description("Duration in milliseconds of the most recently completed accounting request")
                .register(meterRegistry);

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
        LoggingUtil.logDebug(log, M_QUERY, "Fetching service buckets for user: %s", userName);
        Instant startTime = Instant.now();
        return client
                .preparedQuery(QUERY_BALANCE)
                .execute(Tuple.of(userName))
                    .onItem().transform(this::mapRowsToServiceBuckets)
                .onFailure().invoke(error -> {
                    LoggingUtil.logError(log, M_QUERY, error, "Error fetching service buckets for user: %s", userName);
                    exceptionMetricsService.recordException(error, ExceptionMetricsService.Layer.DATABASE, ExceptionMetricsService.Source.DATABASE);
                })
                .onItem().invoke(results -> {
                    Duration duration = Duration.between(startTime, Instant.now());
                    accountingDbExecutionTimer.record(duration);
                    lastAccountingDbExecutionTimeMs.set(duration.toMillis());
                    LoggingUtil.logInfo(log, M_QUERY, "Fetched service buckets for user: %s", userName);
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

            if(row.getLong(RECURRING_FLAG) == 1){
            info.setNextCycleStartDate(row.getLocalDateTime(CYCLE_START_DATE));
            }
            info.setRecurring(row.getLong(RECURRING_FLAG) == 1);

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
            info.setNotificationTemplates(row.getLong(TEMPLATE_ID));
            info.setUserStatus(row.getString(STATUS));

            results.add(info);
        }
        return results;
    }

}
