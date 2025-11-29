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
//todo this method handling 500 tps handling used proper best pracise and optimize code
    private static final Logger log = Logger.getLogger(UserBucketRepository.class);

    final Pool client;

    @Inject
    public UserBucketRepository(Pool client) {
        this.client = client;
    }

    public Uni<List<ServiceBucketInfo>> getServiceBucketsByUserName(String userName) {
        long startTime = System.currentTimeMillis();
        log.infof("Fetching Start service buckets for user: %s", userName);

        return client
                .preparedQuery(QUERY_BALANCE)
                .execute(Tuple.of(userName))
                .onItem().transform(this::mapRowsToServiceBuckets)
                .onFailure().invoke(error ->
                    // Log with full stack trace
                    log.errorf(error, "Error fetching service buckets for user: %s", userName)
                )
                .onItem().invoke(results ->
                       log.infof("Fetched %d service buckets for user: %s in %s ms",
                               results.size(), userName, System.currentTimeMillis() - startTime));
    }

    private List<ServiceBucketInfo> mapRowsToServiceBuckets(RowSet<Row> rows) {
        List<ServiceBucketInfo> results = new ArrayList<>();
        for (Row row : rows) {
            ServiceBucketInfo info = new ServiceBucketInfo();
            info.setBucketId(row.getLong("BUCKET_ID"));
            info.setCurrentBalance(row.getLong("CURRENT_BALANCE"));
            info.setServiceId(row.getLong("ID"));
            info.setRule(row.getString("RULE"));
            info.setPriority(row.getLong("PRIORITY"));
            info.setInitialBalance(row.getLong("INITIAL_BALANCE"));
            info.setStatus(row.getString("STATUS"));
            info.setUsage(row.getLong("USAGE"));
            info.setExpiryDate(row.getLocalDateTime("EXPIRY_DATE"));
            info.setServiceStartDate(row.getLocalDateTime("SERVICE_START_DATE"));
            info.setPlanId(row.getString("PLAN_ID"));
            info.setBucketUser(row.getString("BUCKET_USER"));
            info.setConsumptionLimit(row.getLong("CONSUMPTION_LIMIT"));
            info.setConsumptionTimeWindow(row.getLong("CONSUMPTION_LIMIT_WINDOW"));
            info.setSessionTimeout(row.getString("SESSION_TIMEOUT"));
            info.setTimeWindow(row.getString("TIME_WINDOW"));
            info.setBucketExpiryDate(row.getLocalDateTime("EXPIRATION"));

            results.add(info);
        }
        return results;
    }

}
