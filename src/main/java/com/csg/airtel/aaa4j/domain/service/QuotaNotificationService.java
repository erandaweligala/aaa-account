package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.QuotaNotificationEvent;
import com.csg.airtel.aaa4j.domain.model.ThresholdGlobalTemplates;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for checking quota thresholds and publishing notification events.
 * Monitors quota consumption and triggers notifications when thresholds are exceeded.
 * Templates are loaded from MESSAGE_TEMPLATE table and cached at application startup.
 */
@ApplicationScoped
public class QuotaNotificationService {

    private static final Logger LOG = Logger.getLogger(QuotaNotificationService.class);

    private final AccountProducer accountProducer;
    private final MessageTemplateCacheService templateCacheService;

    public QuotaNotificationService(
            AccountProducer accountProducer,
            MessageTemplateCacheService templateCacheService) {
        this.accountProducer = accountProducer;
        this.templateCacheService = templateCacheService;
    }

    /**
     * Check quota thresholds and publish notifications if thresholds are exceeded.
     *
     * @param userData User session data containing template IDs
     * @param balance Balance/bucket being checked
     * @param oldQuota Previous quota value before update
     * @param newQuota New quota value after update
     * @return Uni that completes when notifications are published
     */
    public Uni<Void> checkAndNotifyThresholds(
            UserSessionData userData,
            Balance balance,
            long oldQuota,
            long newQuota) {

        if (userData == null || balance == null || balance.getInitialBalance() == null) {
            return Uni.createFrom().voidItem();
        }

        // Skip unlimited buckets
        if (balance.isUnlimited()) {
            return Uni.createFrom().voidItem();
        }

        String templateIds = userData.getTemplateIds();
        if (templateIds == null || templateIds.trim().isEmpty()) {
            LOG.debugf("No threshold templates configured for user: %s", userData.getUserName());
            return Uni.createFrom().voidItem();
        }

        List<Long> activeTemplateIds = parseTemplateIds(templateIds);
        if (activeTemplateIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        long initialBalance = balance.getInitialBalance();
        if (initialBalance <= 0) {
            return Uni.createFrom().voidItem();
        }

        // Calculate usage percentages
        double oldUsagePercentage = ((double) (initialBalance - oldQuota) / initialBalance) * 100;
        double newUsagePercentage = ((double) (initialBalance - newQuota) / initialBalance) * 100;

        LOG.debugf("Checking thresholds for user=%s, bucket=%s, oldUsage=%.2f%%, newUsage=%.2f%%",
                userData.getUserName(), balance.getBucketId(), oldUsagePercentage, newUsagePercentage);

        // Check each configured threshold
        List<Uni<Void>> notifications = new ArrayList<>();
        for (Long templateId : activeTemplateIds) {
            Uni<Void> notification = templateCacheService.getTemplate(templateId)
                    .onItem().transformToUni(template -> {
                        if (template != null) {
                            return checkThreshold(
                                    userData, balance, template, oldUsagePercentage, newUsagePercentage, newQuota
                            );
                        }
                        return Uni.createFrom().nullItem();
                    });
            notifications.add(notification);
        }

        if (notifications.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        // Publish all triggered notifications
        return Uni.join().all(notifications).andFailFast()
                .replaceWithVoid()
                .onFailure().invoke(throwable ->
                    LOG.errorf(throwable, "Failed to publish quota notifications for user: %s",
                        userData.getUserName()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Check if a specific threshold has been crossed and publish notification.
     */
    private Uni<Void> checkThreshold(
            UserSessionData userData,
            Balance balance,
            ThresholdGlobalTemplates template,
            double oldUsagePercentage,
            double newUsagePercentage,
            long newQuota) {

        Long threshold = template.getThreshold();
        if (threshold == null) {
            return null;
        }

        // Check if threshold was just crossed (old < threshold <= new)
        if (oldUsagePercentage < threshold && newUsagePercentage >= threshold) {
            LOG.infof("Threshold %d%% exceeded for user=%s, bucket=%s (%.2f%% -> %.2f%%)",
                    threshold, userData.getUserName(), balance.getBucketId(),
                    oldUsagePercentage, newUsagePercentage);

            QuotaNotificationEvent event = createNotificationEvent(
                    userData, balance, template, newQuota
            );

            return accountProducer.produceQuotaNotificationEvent(event);
        }

        return null;
    }

    /**
     * Create notification event from template and balance data.
     */
    private QuotaNotificationEvent createNotificationEvent(
            UserSessionData userData,
            Balance balance,
            ThresholdGlobalTemplates template,
            long availableQuota) {

        String message = formatMessage(
                template.getMassage(),
                userData.getUserName(),
                balance.getBucketId(),
                availableQuota
        );

        String type = template.getThreshold() + "% quota exceeded";

        return new QuotaNotificationEvent(
                LocalDateTime.now(),
                message,
                userData.getUserName(),
                type,
                availableQuota,
                balance.getBucketId(),
                template.getThreshold(),
                balance.getInitialBalance()
        );
    }

    /**
     * Format message template with actual values.
     */
    private String formatMessage(String template, String username, String bucketId, long availableQuota) {
        if (template == null) {
            return String.format("Quota threshold exceeded for user %s", username);
        }

        return template
                .replace("{username}", username)
                .replace("{bucketId}", bucketId)
                .replace("{availableQuota}", String.valueOf(availableQuota));
    }

    /**
     * Parse comma-separated template IDs from string.
     * Expected format: "1,3,5"
     */
    private List<Long> parseTemplateIds(String templateIds) {
        if (templateIds == null || templateIds.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = new ArrayList<>();
        String[] parts = templateIds.split(",");

        for (String part : parts) {
            try {
                Long id = Long.parseLong(part.trim());
                ids.add(id);
            } catch (NumberFormatException e) {
                LOG.warnf("Invalid template ID: %s", part);
            }
        }

        return ids;
    }

    /**
     * Get threshold template by ID (for external configuration/management).
     * Retrieves from cache service.
     *
     * @deprecated Use templateCacheService.getTemplate() directly for reactive access
     */
    @Deprecated
    public Uni<ThresholdGlobalTemplates> getTemplate(Long templateId) {
        return templateCacheService.getTemplate(templateId);
    }

    /**
     * Refresh template cache from database.
     * Useful for runtime updates without restart.
     */
    public Uni<Void> refreshTemplateCache() {
        LOG.info("Triggering template cache refresh");
        return templateCacheService.refreshCache();
    }
}
