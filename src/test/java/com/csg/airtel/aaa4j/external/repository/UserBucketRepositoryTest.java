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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Iterator;
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

    @InjectMocks
    private UserBucketRepository repository;

    private String userName;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        userName = "testuser";
        now = LocalDateTime.now();
    }

    @Test
    void testGetServiceBucketsByUserNameSuccess() {
        when(row.getLong("BUCKET_ID")).thenReturn(123L);
        when(row.getLong("CURRENT_BALANCE")).thenReturn(5000L);
        when(row.getLong("ID")).thenReturn(456L);
        when(row.getString("RULE")).thenReturn("FIFO");
        when(row.getLong("PRIORITY")).thenReturn(1L);
        when(row.getLong("INITIAL_BALANCE")).thenReturn(10000L);
        when(row.getString("STATUS")).thenReturn("ACTIVE");
        when(row.getLong("USAGE")).thenReturn(5000L);
        when(row.getLocalDateTime("EXPIRY_DATE")).thenReturn(now.plusDays(30));
        when(row.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now);
        when(row.getString("PLAN_ID")).thenReturn("PLAN001");
        when(row.getString("BUCKET_USER")).thenReturn(userName);
        when(row.getLong("CONSUMPTION_LIMIT")).thenReturn(1000L);
        when(row.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(3600L);
        when(row.getString("SESSION_TIMEOUT")).thenReturn("1800");
        when(row.getString("TIME_WINDOW")).thenReturn("HOURLY");
        when(row.getLocalDateTime("EXPIRATION")).thenReturn(now.plusMonths(6));

        when(rowSet.size()).thenReturn(1);
        doReturn(Arrays.asList(row).iterator()).when(rowSet).iterator();

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);

        List<ServiceBucketInfo> result = repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals(1, result.size());

        ServiceBucketInfo bucket = result.get(0);
        assertEquals(123L, bucket.getBucketId());
        assertEquals(5000L, bucket.getCurrentBalance());
        assertEquals(456L, bucket.getServiceId());
        assertEquals("FIFO", bucket.getRule());
        assertEquals(1L, bucket.getPriority());
        assertEquals(10000L, bucket.getInitialBalance());
        assertEquals("ACTIVE", bucket.getStatus());
        assertEquals(userName, bucket.getBucketUser());

        verify(client).preparedQuery(anyString());
        verify(preparedQuery).execute(any(Tuple.class));
    }

    @Test
    void testGetServiceBucketsByUserNameEmptyResult() {
        when(rowSet.size()).thenReturn(0);
        doReturn(Arrays.<Row>asList().iterator()).when(rowSet).iterator();

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);

        List<ServiceBucketInfo> result = repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertTrue(result.isEmpty());

        verify(client).preparedQuery(anyString());
    }

    @Test
    void testGetServiceBucketsByUserNameMultipleResults() {
        Row row2 = mock(Row.class);

        when(row.getLong("BUCKET_ID")).thenReturn(123L);
        when(row.getLong("CURRENT_BALANCE")).thenReturn(5000L);
        when(row.getLong("ID")).thenReturn(456L);
        when(row.getString("RULE")).thenReturn("FIFO");
        when(row.getLong("PRIORITY")).thenReturn(1L);
        when(row.getLong("INITIAL_BALANCE")).thenReturn(10000L);
        when(row.getString("STATUS")).thenReturn("ACTIVE");
        when(row.getLong("USAGE")).thenReturn(5000L);
        when(row.getLocalDateTime("EXPIRY_DATE")).thenReturn(now);
        when(row.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now);
        when(row.getString("PLAN_ID")).thenReturn("PLAN001");
        when(row.getString("BUCKET_USER")).thenReturn(userName);
        when(row.getLong("CONSUMPTION_LIMIT")).thenReturn(1000L);
        when(row.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(3600L);
        when(row.getString("SESSION_TIMEOUT")).thenReturn("1800");
        when(row.getString("TIME_WINDOW")).thenReturn("HOURLY");
        when(row.getLocalDateTime("EXPIRATION")).thenReturn(now);

        when(row2.getLong("BUCKET_ID")).thenReturn(124L);
        when(row2.getLong("CURRENT_BALANCE")).thenReturn(3000L);
        when(row2.getLong("ID")).thenReturn(457L);
        when(row2.getString("RULE")).thenReturn("LIFO");
        when(row2.getLong("PRIORITY")).thenReturn(2L);
        when(row2.getLong("INITIAL_BALANCE")).thenReturn(8000L);
        when(row2.getString("STATUS")).thenReturn("ACTIVE");
        when(row2.getLong("USAGE")).thenReturn(5000L);
        when(row2.getLocalDateTime("EXPIRY_DATE")).thenReturn(now);
        when(row2.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now);
        when(row2.getString("PLAN_ID")).thenReturn("PLAN002");
        when(row2.getString("BUCKET_USER")).thenReturn(userName);
        when(row2.getLong("CONSUMPTION_LIMIT")).thenReturn(2000L);
        when(row2.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(7200L);
        when(row2.getString("SESSION_TIMEOUT")).thenReturn("3600");
        when(row2.getString("TIME_WINDOW")).thenReturn("DAILY");
        when(row2.getLocalDateTime("EXPIRATION")).thenReturn(now);

        when(rowSet.size()).thenReturn(2);
        doReturn(Arrays.asList(row, row2).iterator()).when(rowSet).iterator();

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);

        List<ServiceBucketInfo> result = repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(123L, result.get(0).getBucketId());
        assertEquals(124L, result.get(1).getBucketId());
    }

    @Test
    void testGetServiceBucketsByUserNameFailure() {
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class)))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Database error")));

        UniAssertSubscriber<List<ServiceBucketInfo>> subscriber = repository
                .getServiceBucketsByUserName(userName)
                .subscribe()
                .withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitFailure();
        Throwable failure = subscriber.getFailure();

        assertNotNull(failure);
        assertTrue(failure instanceof RuntimeException);
        assertEquals("Database error", failure.getMessage());
    }

    @Test
    void testGetServiceBucketsByUserNameWithNullValues() {
        when(row.getLong("BUCKET_ID")).thenReturn(123L);
        when(row.getLong("CURRENT_BALANCE")).thenReturn(5000L);
        when(row.getLong("ID")).thenReturn(456L);
        when(row.getString("RULE")).thenReturn(null);
        when(row.getLong("PRIORITY")).thenReturn(1L);
        when(row.getLong("INITIAL_BALANCE")).thenReturn(10000L);
        when(row.getString("STATUS")).thenReturn("ACTIVE");
        when(row.getLong("USAGE")).thenReturn(5000L);
        when(row.getLocalDateTime("EXPIRY_DATE")).thenReturn(null);
        when(row.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now);
        when(row.getString("PLAN_ID")).thenReturn(null);
        when(row.getString("BUCKET_USER")).thenReturn(userName);
        when(row.getLong("CONSUMPTION_LIMIT")).thenReturn(null);
        when(row.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(null);
        when(row.getString("SESSION_TIMEOUT")).thenReturn(null);
        when(row.getString("TIME_WINDOW")).thenReturn(null);
        when(row.getLocalDateTime("EXPIRATION")).thenReturn(null);

        when(rowSet.size()).thenReturn(1);
        doReturn(Arrays.asList(row).iterator()).when(rowSet).iterator();

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);

        List<ServiceBucketInfo> result = repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals(1, result.size());

        ServiceBucketInfo bucket = result.get(0);
        assertEquals(123L, bucket.getBucketId());
        assertEquals(5000L, bucket.getCurrentBalance());
        assertNull(bucket.getRule());
        assertNull(bucket.getPlanId());
        assertNull(bucket.getExpiryDate());
        assertNull(bucket.getConsumptionLimit());
        assertNull(bucket.getConsumptionTimeWindow());
        assertNull(bucket.getSessionTimeout());
        assertNull(bucket.getTimeWindow());
        assertNull(bucket.getBucketExpiryDate());
    }

    @Test
    void testGetServiceBucketsByUserNameVerifyAllFieldsMapped() {
        when(row.getLong("BUCKET_ID")).thenReturn(999L);
        when(row.getLong("CURRENT_BALANCE")).thenReturn(7500L);
        when(row.getLong("ID")).thenReturn(888L);
        when(row.getString("RULE")).thenReturn("PRIORITY");
        when(row.getLong("PRIORITY")).thenReturn(5L);
        when(row.getLong("INITIAL_BALANCE")).thenReturn(15000L);
        when(row.getString("STATUS")).thenReturn("SUSPENDED");
        when(row.getLong("USAGE")).thenReturn(7500L);
        when(row.getLocalDateTime("EXPIRY_DATE")).thenReturn(now.plusDays(15));
        when(row.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now.minusDays(5));
        when(row.getString("PLAN_ID")).thenReturn("PREMIUM_PLAN");
        when(row.getString("BUCKET_USER")).thenReturn("premium_user");
        when(row.getLong("CONSUMPTION_LIMIT")).thenReturn(50000L);
        when(row.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(86400L);
        when(row.getString("SESSION_TIMEOUT")).thenReturn("7200");
        when(row.getString("TIME_WINDOW")).thenReturn("WEEKLY");
        when(row.getLocalDateTime("EXPIRATION")).thenReturn(now.plusYears(1));

        when(rowSet.size()).thenReturn(1);
        doReturn(Arrays.asList(row).iterator()).when(rowSet).iterator();

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);

        List<ServiceBucketInfo> result = repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals(1, result.size());

        ServiceBucketInfo bucket = result.get(0);
        assertEquals(999L, bucket.getBucketId());
        assertEquals(7500L, bucket.getCurrentBalance());
        assertEquals(888L, bucket.getServiceId());
        assertEquals("PRIORITY", bucket.getRule());
        assertEquals(5L, bucket.getPriority());
        assertEquals(15000L, bucket.getInitialBalance());
        assertEquals("SUSPENDED", bucket.getStatus());
        assertEquals(7500L, bucket.getUsage());
        assertEquals(now.plusDays(15), bucket.getExpiryDate());
        assertEquals(now.minusDays(5), bucket.getServiceStartDate());
        assertEquals("PREMIUM_PLAN", bucket.getPlanId());
        assertEquals("premium_user", bucket.getBucketUser());
        assertEquals(50000L, bucket.getConsumptionLimit());
        assertEquals(86400L, bucket.getConsumptionTimeWindow());
        assertEquals("7200", bucket.getSessionTimeout());
        assertEquals("WEEKLY", bucket.getTimeWindow());
        assertEquals(now.plusYears(1), bucket.getBucketExpiryDate());
    }

    @Test
    void testGetServiceBucketsByUserNameLargeResultSet() {
        // Create 100 mock rows to test pre-sizing optimization
        List<Row> mockRows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Row mockRow = mock(Row.class);
            when(mockRow.getLong("BUCKET_ID")).thenReturn((long) i);
            when(mockRow.getLong("CURRENT_BALANCE")).thenReturn(1000L);
            when(mockRow.getLong("ID")).thenReturn((long) (i * 10));
            when(mockRow.getString("RULE")).thenReturn("FIFO");
            when(mockRow.getLong("PRIORITY")).thenReturn(1L);
            when(mockRow.getLong("INITIAL_BALANCE")).thenReturn(2000L);
            when(mockRow.getString("STATUS")).thenReturn("ACTIVE");
            when(mockRow.getLong("USAGE")).thenReturn(1000L);
            when(mockRow.getLocalDateTime("EXPIRY_DATE")).thenReturn(now);
            when(mockRow.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now);
            when(mockRow.getString("PLAN_ID")).thenReturn("PLAN_" + i);
            when(mockRow.getString("BUCKET_USER")).thenReturn(userName);
            when(mockRow.getLong("CONSUMPTION_LIMIT")).thenReturn(5000L);
            when(mockRow.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(3600L);
            when(mockRow.getString("SESSION_TIMEOUT")).thenReturn("1800");
            when(mockRow.getString("TIME_WINDOW")).thenReturn("HOURLY");
            when(mockRow.getLocalDateTime("EXPIRATION")).thenReturn(now);
            mockRows.add(mockRow);
        }

        when(rowSet.size()).thenReturn(100);
        doReturn(mockRows.iterator()).when(rowSet).iterator();

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);

        List<ServiceBucketInfo> result = repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals(100, result.size());

        // Verify first and last elements
        assertEquals(0L, result.get(0).getBucketId());
        assertEquals(99L, result.get(99).getBucketId());
    }

    @Test
    void testGetServiceBucketsByUserNameZeroLongValues() {
        when(row.getLong("BUCKET_ID")).thenReturn(0L);
        when(row.getLong("CURRENT_BALANCE")).thenReturn(0L);
        when(row.getLong("ID")).thenReturn(0L);
        when(row.getString("RULE")).thenReturn("FIFO");
        when(row.getLong("PRIORITY")).thenReturn(0L);
        when(row.getLong("INITIAL_BALANCE")).thenReturn(0L);
        when(row.getString("STATUS")).thenReturn("ACTIVE");
        when(row.getLong("USAGE")).thenReturn(0L);
        when(row.getLocalDateTime("EXPIRY_DATE")).thenReturn(now);
        when(row.getLocalDateTime("SERVICE_START_DATE")).thenReturn(now);
        when(row.getString("PLAN_ID")).thenReturn("PLAN001");
        when(row.getString("BUCKET_USER")).thenReturn(userName);
        when(row.getLong("CONSUMPTION_LIMIT")).thenReturn(0L);
        when(row.getLong("CONSUMPTION_LIMIT_WINDOW")).thenReturn(0L);
        when(row.getString("SESSION_TIMEOUT")).thenReturn("0");
        when(row.getString("TIME_WINDOW")).thenReturn("0-24");
        when(row.getLocalDateTime("EXPIRATION")).thenReturn(now);

        when(rowSet.size()).thenReturn(1);
        doReturn(Arrays.asList(row).iterator()).when(rowSet).iterator();

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);

        List<ServiceBucketInfo> result = repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals(1, result.size());

        ServiceBucketInfo bucket = result.get(0);
        assertEquals(0L, bucket.getBucketId());
        assertEquals(0L, bucket.getCurrentBalance());
        assertEquals(0L, bucket.getPriority());
        assertEquals(0L, bucket.getInitialBalance());
        assertEquals(0L, bucket.getUsage());
        assertEquals(0L, bucket.getConsumptionLimit());
        assertEquals(0L, bucket.getConsumptionTimeWindow());
    }
}
