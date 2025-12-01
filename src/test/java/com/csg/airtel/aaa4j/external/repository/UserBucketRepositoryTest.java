package com.csg.airtel.aaa4j.external.repository;

import com.csg.airtel.aaa4j.domain.model.ServiceBucketInfo;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import io.vertx.mutiny.sqlclient.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
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

        // FIX: mock RowIterator
        RowIterator<Row> rowIterator = mock(RowIterator.class);
        when(rowIterator.hasNext()).thenReturn(true, false);
        when(rowIterator.next()).thenReturn(row);

        when(rowSet.iterator()).thenReturn(rowIterator);
        when(rowSet.size()).thenReturn(1);

        when(preparedQuery.execute(any(Tuple.class)))
                .thenReturn(Uni.createFrom().item(rowSet));

        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);

        // Execute
        List<ServiceBucketInfo> result = repository.getServiceBucketsByUserName(userName)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals(1, result.size());
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
