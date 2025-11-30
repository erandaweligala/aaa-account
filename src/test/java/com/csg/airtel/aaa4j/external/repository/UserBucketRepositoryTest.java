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
}
