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

@ApplicationScoped
public class QuotaNotificationService {

    private static final Logger LOG = Logger.getLogger(QuotaNotificationService.class);

    private final AccountProducer accountProducer;
    private final MessageTemplateCacheService templateCacheService;
    private final NotificationTrackingService notificationTrackingService;

    public QuotaNotificationService(
            AccountProducer accountProducer,
            MessageTemplateCacheService templateCacheService,
            NotificationTrackingService notificationTrackingService) {
        this.accountProducer = accountProducer;
        this.templateCacheService = templateCacheService;
        this.notificationTrackingService = notificationTrackingService;
    }

    /**
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
                                    userData, balance, template, templateId, oldUsagePercentage, newUsagePercentage, newQuota
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
     * Prevents duplicate notifications by checking if the same notification was already sent.
     */
    private Uni<Void> checkThreshold(
            UserSessionData userData,
            Balance balance,
            ThresholdGlobalTemplates template,
            Long templateId,
            double oldUsagePercentage,
            double newUsagePercentage,
            long newQuota) {

        Long threshold = template.getThreshold();
        if (threshold == null) {
            return Uni.createFrom().voidItem();
        }

        // Check if threshold was just crossed (old < threshold <= new)
        if (oldUsagePercentage < threshold && newUsagePercentage >= threshold) {
            LOG.infof("Threshold %d%% exceeded for user=%s, bucket=%s (%.2f%% -> %.2f%%)",
                    threshold, userData.getUserName(), balance.getBucketId(),
                    oldUsagePercentage, newUsagePercentage);

            // Check if this notification was already sent to prevent duplicates
            return notificationTrackingService.isDuplicateNotification(
                    userData.getUserName(),
                    templateId,
                    balance.getBucketId(),
                    threshold
            ).onItem().transformToUni(isDuplicate -> {
                if (Boolean.TRUE.equals(isDuplicate)) {
                    LOG.infof("Skipping duplicate notification for user=%s, templateId=%d, bucket=%s, threshold=%d%%",
                            userData.getUserName(), templateId, balance.getBucketId(), threshold);
                    return Uni.createFrom().voidItem();
                }

                // Create and send notification
                QuotaNotificationEvent event = createNotificationEvent(
                        userData, balance, template, templateId, newQuota
                );

                // Mark notification as sent and publish the event
                return notificationTrackingService.markNotificationSent(
                        userData.getUserName(),
                        templateId,
                        balance.getBucketId(),
                        threshold
                ).onItem().transformToUni(v -> accountProducer.produceQuotaNotificationEvent(event));
            });
        }

        return Uni.createFrom().voidItem();
    }

    /**
     * Create notification event from template and balance data.
     */
    private QuotaNotificationEvent createNotificationEvent(
            UserSessionData userData,
            Balance balance,
            ThresholdGlobalTemplates template,
            Long templateId,
            long availableQuota) {

        String message = formatMessage(
                template,
                userData,
                balance,
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
                balance.getInitialBalance(),
                templateId
        );
    }

    /**
     * Format message template with actual values dynamically based on template params.
     */
    private String formatMessage(
            ThresholdGlobalTemplates template,
            UserSessionData userData,
            Balance balance,
            long availableQuota) {

        if (template == null || template.getMassage() == null) {
            return String.format("Quota threshold exceeded for user %s",
                    userData != null ? userData.getUserName() : "unknown");
        }

        String message = template.getMassage();
        String[] params = template.getParams();

        // If no params defined, return message as-is
        if (params == null || params.length == 0) {
            return message;
        }

        // Create a map of available parameter values
        Map<String, String> paramValues = new HashMap<>();
        paramValues.put("MOCN", userData != null ? userData.getUserName() : "");
        paramValues.put("PLAN_NAME", balance != null ? balance.getBucketId() : "");
        paramValues.put("UTILIZED_QUOTA", String.valueOf(availableQuota));
        paramValues.put("QUOTA_PERCENTAGE", template.getThreshold() != null ? template.getThreshold().toString() : "");
        paramValues.put("INITIAL_QUOTA", balance != null && balance.getInitialBalance() != null
                ? balance.getInitialBalance().toString() : "");

        // Dynamically replace each parameter in the template
        for (String param : params) {
            String value = paramValues.getOrDefault(param, "");
            message = message.replace("{" + param + "}", value);
        }

        return message;
    }

    /**
     * Parse comma-separated template IDs from string.
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
     * Refresh template cache from database.
     * Useful for runtime updates without restart.
     */
    public Uni<Void> refreshTemplateCache() {
        LOG.info("Triggering template cache refresh");
        return templateCacheService.refreshCache();
    }
}
