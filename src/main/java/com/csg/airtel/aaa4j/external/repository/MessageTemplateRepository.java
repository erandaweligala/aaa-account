package com.csg.airtel.aaa4j.external.repository;

import com.csg.airtel.aaa4j.domain.model.MessageTemplate;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.csg.airtel.aaa4j.domain.constant.SQLConstant.QUERY_MESSAGE_TEMPLATES;

/**
 * Repository for MESSAGE_TEMPLATE table operations.
 * Handles fetching and mapping message templates from the database.
 */
@ApplicationScoped
public class MessageTemplateRepository {

    private static final Logger LOG = Logger.getLogger(MessageTemplateRepository.class);

    // Column name constants for MESSAGE_TEMPLATE table
    private static final String COL_TEMPLATE_ID = "TEMPLATE_ID";
    private static final String COL_STATUS = "STATUS";
    private static final String COL_TEMPLATE_NAME = "TEMPLATE_NAME";
    private static final String COL_MESSAGE_TYPE = "MESSAGE_TYPE";
    private static final String COL_DAYS_TO_EXPIRE = "DAYS_TO_EXPIRE";
    private static final String COL_QUOTA_PERCENTAGE = "QUOTA_PERCENTAGE";
    private static final String COL_MESSAGE_CONTENT = "MESSAGE_CONTENT";
    private static final String COL_CREATED_DATE = "CREATED_DATE";
    private static final String COL_CREATED_BY = "CREATED_BY";
    private static final String COL_MODIFIED_DATE = "MODIFIED_DATE";
    private static final String COL_MODIFIED_BY = "MODIFIED_BY";

    private static final int DEFAULT_TEMPLATE_LIST_CAPACITY = 20;

    private final Pool client;

    @Inject
    public MessageTemplateRepository(Pool client) {
        this.client = client;
    }

    /**
     * Fetches all active message templates from the database.
     * Only templates with STATUS = 'ACTIVE' are returned.
     *
     * @return Uni containing list of MessageTemplate
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 10000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 3,
            delay = 200,
            maxDuration = 15000
    )
    public Uni<List<MessageTemplate>> getAllActiveTemplates() {
        LOG.debug("Fetching all active message templates");

        return client
                .preparedQuery(QUERY_MESSAGE_TEMPLATES)
                .execute()
                .onItem().transform(this::mapRowsToMessageTemplates)
                .onFailure().invoke(error ->
                        LOG.error("Error fetching message templates from database", error))
                .onItem().invoke(results ->
                        LOG.infof("Fetched %d active message templates from database", results.size()));
    }

    /**
     * Maps database rows to MessageTemplate objects.
     *
     * @param rows the database result set
     * @return list of mapped MessageTemplate objects
     */
    private List<MessageTemplate> mapRowsToMessageTemplates(RowSet<Row> rows) {
        int rowCount = rows.size();
        List<MessageTemplate> results = new ArrayList<>(rowCount > 0 ? rowCount : DEFAULT_TEMPLATE_LIST_CAPACITY);

        for (Row row : rows) {
            MessageTemplate template = new MessageTemplate();

            // Primary identifier
            template.setTemplateId(row.getLong(COL_TEMPLATE_ID));

            // Template metadata
            template.setStatus(row.getString(COL_STATUS));
            template.setTemplateName(row.getString(COL_TEMPLATE_NAME));
            template.setMessageType(row.getString(COL_MESSAGE_TYPE));

            // Type-specific fields (may be null based on MESSAGE_TYPE)
            Integer daysToExpire = row.getInteger(COL_DAYS_TO_EXPIRE);
            template.setDaysToExpire(daysToExpire);

            Integer quotaPercentage = row.getInteger(COL_QUOTA_PERCENTAGE);
            template.setQuotaPercentage(quotaPercentage);

            // Message content (CLOB)
            String messageContent = row.getString(COL_MESSAGE_CONTENT);
            template.setMessageContent(messageContent);

            // Audit fields
            template.setCreatedDate(row.getLocalDateTime(COL_CREATED_DATE));
            template.setCreatedBy(row.getString(COL_CREATED_BY));
            template.setModifiedDate(row.getLocalDateTime(COL_MODIFIED_DATE));
            template.setModifiedBy(row.getString(COL_MODIFIED_BY));

            results.add(template);
        }

        return results;
    }
}
