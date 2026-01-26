package com.csg.airtel.aaa4j.external.repository;

import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserBucketRepositoryTest {

    @Mock
    private Pool client;

    @Mock
    private PreparedQuery<RowSet<Row>> preparedQuery;

    @Mock
    private RowSet<Row> rowSet;

    @Mock
    private Row row;

    private UserBucketRepository repository;

    @BeforeEach
    void setUp() {
        repository = new UserBucketRepository(client);
    }

    @Test
    void testGetServiceBucketsByUserName_Success() {
        String userName = "testuser";
        LocalDateTime now = LocalDateTime.now();

        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));

        List<Row> rows = new ArrayList<>();
        rows.add(row);
        when(rowSet.size()).thenReturn(1);
        when(rowSet.iterator()).thenReturn(rows.iterator());

        when(row.getLong("ID")).thenReturn(1L);
        when(row.getLong("BUCKET_ID")).thenReturn(100L);
        when(row.getString("BUCKET_USER")).thenReturn(userName);
        when(row.getString("PLAN_ID")).thenReturn("plan-1");
        when(row.getString("STATUS")).thenReturn("Active");
        when(row.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now);
        when(row.getLocalDateTime("EXPIRY_DATE")).thenReturn(now.plusDays(30));
        when(row.getString("RULE")).thenReturn("rule-1");
        when(row.getLong("PRIORITY")).thenReturn(1L);
        when(row.getLong("INITIAL_BALANCE")).thenReturn(2000L);
        when(row.getLong("CURRENT_BALANCE")).thenReturn(1000L);
        when(row.getLong("USAGE")).thenReturn(1000L);
        when(row.getLong("CONSUMPTION_LIMIT")).thenReturn(500L);
        when(row.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(30L);
        when(row.getString("TIME_WINDOW")).thenReturn("0-24");
        when(row.getLocalDateTime("EXPIRATION")).thenReturn(now.plusDays(60));
        when(row.getLong("IS_UNLIMITED")).thenReturn(0L);
        when(row.getLong("IS_GROUP")).thenReturn(0L);
        when(row.getLong("CONCURRENCY")).thenReturn(2L);
        when(row.getString("SESSION_TIMEOUT")).thenReturn("3600");
        when(row.getLong("TEMPLATE_ID")).thenReturn(1L);

        List<ServiceBucketInfo> buckets = repository.getServiceBucketsByUserName(userName)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(buckets);
        assertEquals(1, buckets.size());
        ServiceBucketInfo bucket = buckets.get(0);
        assertEquals(1L, bucket.getServiceId());
        assertEquals(100L, bucket.getBucketId());
        assertEquals(userName, bucket.getBucketUser());
        assertEquals("Active", bucket.getStatus());
        assertEquals(2000L, bucket.getInitialBalance());
        assertEquals(1000L, bucket.getCurrentBalance());
        assertFalse(bucket.isUnlimited());
        assertFalse(bucket.isGroup());

        verify(client).preparedQuery(anyString());
        verify(preparedQuery).execute(any(Tuple.class));
    }

    @Test
    void testGetServiceBucketsByUserName_EmptyResult() {
        String userName = "testuser";

        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
        when(rowSet.size()).thenReturn(0);
        when(rowSet.iterator()).thenReturn(new ArrayList<Row>().iterator());

        List<ServiceBucketInfo> buckets = repository.getServiceBucketsByUserName(userName)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(buckets);
        assertTrue(buckets.isEmpty());
    }

    @Test
    void testGetServiceBucketsByUserName_DatabaseError() {
        String userName = "testuser";

        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class)))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("DB error")));

        UniAssertSubscriber<List<ServiceBucketInfo>> subscriber =
            repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitFailure();
        assertNotNull(subscriber.getFailure());
    }

    @Test
    void testGetServiceBucketsByUserName_NullBalances() {
        String userName = "testuser";
        LocalDateTime now = LocalDateTime.now();

        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));

        List<Row> rows = new ArrayList<>();
        rows.add(row);
        when(rowSet.size()).thenReturn(1);
        when(rowSet.iterator()).thenReturn(rows.iterator());

        when(row.getLong("ID")).thenReturn(1L);
        when(row.getLong("BUCKET_ID")).thenReturn(100L);
        when(row.getString("BUCKET_USER")).thenReturn(userName);
        when(row.getString("PLAN_ID")).thenReturn("plan-1");
        when(row.getString("STATUS")).thenReturn("Active");
        when(row.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now);
        when(row.getLocalDateTime("EXPIRY_DATE")).thenReturn(now.plusDays(30));
        when(row.getString("RULE")).thenReturn("rule-1");
        when(row.getLong("PRIORITY")).thenReturn(1L);
        when(row.getLong("INITIAL_BALANCE")).thenReturn(null);
        when(row.getLong("CURRENT_BALANCE")).thenReturn(null);
        when(row.getLong("USAGE")).thenReturn(0L);
        when(row.getLong("CONSUMPTION_LIMIT")).thenReturn(null);
        when(row.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(null);
        when(row.getString("TIME_WINDOW")).thenReturn("0-24");
        when(row.getLocalDateTime("EXPIRATION")).thenReturn(now.plusDays(60));
        when(row.getLong("IS_UNLIMITED")).thenReturn(1L);
        when(row.getLong("IS_GROUP")).thenReturn(1L);
        when(row.getLong("CONCURRENCY")).thenReturn(2L);
        when(row.getString("SESSION_TIMEOUT")).thenReturn("3600");
        when(row.getLong("TEMPLATE_ID")).thenReturn(1L);

        List<ServiceBucketInfo> buckets = repository.getServiceBucketsByUserName(userName)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(buckets);
        assertEquals(1, buckets.size());
        ServiceBucketInfo bucket = buckets.get(0);
        assertEquals(0L, bucket.getInitialBalance());
        assertEquals(0L, bucket.getCurrentBalance());
        assertEquals(0L, bucket.getConsumptionLimit());
        assertEquals(0L, bucket.getConsumptionTimeWindow());
        assertTrue(bucket.isUnlimited());
        assertTrue(bucket.isGroup());
    }
}
