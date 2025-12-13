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
 */
@ApplicationScoped
public class QuotaNotificationService {

    private static final Logger LOG = Logger.getLogger(QuotaNotificationService.class);

    private final AccountProducer accountProducer;
    private final Map<Long, ThresholdGlobalTemplates> thresholdTemplates;

    public QuotaNotificationService(AccountProducer accountProducer) {
        this.accountProducer = accountProducer;
        this.thresholdTemplates = initializeDefaultThresholds();
    }

    /**
     * Initialize default threshold templates for 50%, 80%, and 100% quota consumption.
     */

    //todo need get DB FROM MESSAGE_TEMPLATE application statup add to chache using kay =  TEMPLATE_ID , value ThresholdGlobalTemplates
    private Map<Long, ThresholdGlobalTemplates> initializeDefaultThresholds() {
        Map<Long, ThresholdGlobalTemplates> templates = new HashMap<>();

        ThresholdGlobalTemplates threshold50 = new ThresholdGlobalTemplates();
        threshold50.setThreshold(50L);
        threshold50.setMassage("User {username} has consumed 50% of data quota. Remaining: {availableQuota} bytes in bucket {bucketId}");
        threshold50.setParams(new String[]{"username", "availableQuota", "bucketId"});
        templates.put(1L, threshold50);

        ThresholdGlobalTemplates threshold80 = new ThresholdGlobalTemplates();
        threshold80.setThreshold(80L);
        threshold80.setMassage("User {username} has consumed 80% of data quota. Remaining: {availableQuota} bytes in bucket {bucketId}");
        threshold80.setParams(new String[]{"username", "availableQuota", "bucketId"});
        templates.put(3L, threshold80);

        ThresholdGlobalTemplates threshold100 = new ThresholdGlobalTemplates();
        threshold100.setThreshold(100L);
        threshold100.setMassage("User {username} has exhausted 100% of data quota in bucket {bucketId}");
        threshold100.setParams(new String[]{"username", "bucketId"});
        templates.put(5L, threshold100);

        LOG.info("Initialized default threshold templates: 50%, 80%, 100%");
        return templates;
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
            ThresholdGlobalTemplates template = thresholdTemplates.get(templateId);
            if (template != null) {
                Uni<Void> notification = checkThreshold(
                        userData, balance, template, oldUsagePercentage, newUsagePercentage, newQuota
                );
                if (notification != null) {
                    notifications.add(notification);
                }
            }
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
     */
    public ThresholdGlobalTemplates getTemplate(Long templateId) {
        return thresholdTemplates.get(templateId);
    }

    /**
     * Update or add threshold template (for external configuration/management).
     */
    public void updateTemplate(Long templateId, ThresholdGlobalTemplates template) {
        thresholdTemplates.put(templateId, template);
        LOG.infof("Updated threshold template %d: %d%% - %s",
                templateId, template.getThreshold(), template.getMassage());
    }
}
