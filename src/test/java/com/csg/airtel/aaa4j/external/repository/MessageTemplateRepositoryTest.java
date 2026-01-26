package com.csg.airtel.aaa4j.external.repository;

import com.csg.airtel.aaa4j.domain.model.MessageTemplate;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageTemplateRepositoryTest {

    @Mock
    private Pool client;

    @Mock
    private PreparedQuery<RowSet<Row>> preparedQuery;

    @Mock
    private RowSet<Row> rowSet;

    @Mock
    private Row row;

    private MessageTemplateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MessageTemplateRepository(client);
    }

    @Test
    void testGetAllActiveTemplates_Success() {
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute()).thenReturn(Uni.createFrom().item(rowSet));

        List<Row> rows = new ArrayList<>();
        rows.add(row);
        when(rowSet.size()).thenReturn(1);
        when(rowSet.iterator()).thenReturn(rows.iterator());

        when(row.getLong("SUPER_TEMPLATE_ID")).thenReturn(1L);
        when(row.getLong("ID")).thenReturn(100L);
        when(row.getString("MESSAGE_TYPE")).thenReturn("USAGE");
        when(row.getInteger("QUOTA_PERCENTAGE")).thenReturn(80);
        when(row.getString("MESSAGE_CONTENT")).thenReturn("Test message");

        List<MessageTemplate> templates = repository.getAllActiveTemplates()
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(templates);
        assertEquals(1, templates.size());
        MessageTemplate template = templates.get(0);
        assertEquals(1L, template.getSuperTemplateId());
        assertEquals(100L, template.getTemplateId());
        assertEquals("USAGE", template.getMessageType());
        assertEquals(80, template.getQuotaPercentage());
        assertEquals("Test message", template.getMessageContent());

        verify(client).preparedQuery(anyString());
        verify(preparedQuery).execute();
    }

    @Test
    void testGetAllActiveTemplates_EmptyResult() {
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute()).thenReturn(Uni.createFrom().item(rowSet));
        when(rowSet.size()).thenReturn(0);
        when(rowSet.iterator()).thenReturn(new ArrayList<Row>().iterator());

        List<MessageTemplate> templates = repository.getAllActiveTemplates()
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(templates);
        assertTrue(templates.isEmpty());
    }

    @Test
    void testGetAllActiveTemplates_DatabaseError() {
        when(client.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute()).thenReturn(Uni.createFrom().failure(new RuntimeException("DB error")));

        UniAssertSubscriber<List<MessageTemplate>> subscriber = repository.getAllActiveTemplates()
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        subscriber.awaitFailure();
        assertNotNull(subscriber.getFailure());
    }
}
