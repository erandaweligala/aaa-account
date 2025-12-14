package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.config.BucketExpirationConfig;
import com.csg.airtel.aaa4j.domain.model.ExpiringBucketInfo;
import com.csg.airtel.aaa4j.domain.model.QuotaNotificationEvent;
import com.csg.airtel.aaa4j.domain.model.ThresholdGlobalTemplates;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.repository.UserBucketRepository;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduler service that sends bucket expiration notifications before buckets expire.
 * Checks buckets expiring soon and sends notifications based on MESSAGE_TEMPLATE configurations.
 */
@ApplicationScoped
public class BucketExpirationNotificationScheduler {

    private static final Logger LOG = Logger.getLogger(BucketExpirationNotificationScheduler.class);

    private final BucketExpirationConfig config;
    private final UserBucketRepository userBucketRepository;
    private final MessageTemplateCacheService templateCacheService;
    private final NotificationTrackingService notificationTrackingService;
    private final AccountProducer accountProducer;

    @Inject
    public BucketExpirationNotificationScheduler(
            BucketExpirationConfig config,
            UserBucketRepository userBucketRepository,
            MessageTemplateCacheService templateCacheService,
            NotificationTrackingService notificationTrackingService,
            AccountProducer accountProducer) {
        this.config = config;
        this.userBucketRepository = userBucketRepository;
        this.templateCacheService = templateCacheService;
        this.notificationTrackingService = notificationTrackingService;
        this.accountProducer = accountProducer;
    }

    /**
     * Scheduled task to check bucket expiration and send notifications.
     * Runs at configurable intervals defined by bucket-expiration.scheduler-interval.
     */
    @Scheduled(every = "${bucket-expiration.scheduler-interval:1h}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void checkBucketExpirations() {
        if (!config.enabled()) {
            LOG.debug("Bucket expiration notification scheduler is disabled, skipping execution");
            return;
        }

        long startTime = System.currentTimeMillis();
        int maxDaysAhead = config.maxDaysAhead();

        LOG.infof("Starting bucket expiration check with max days ahead: %d", maxDaysAhead);

        AtomicInteger totalNotificationsSent = new AtomicInteger(0);
        AtomicInteger totalBucketsChecked = new AtomicInteger(0);

        // Fetch buckets expiring within the configured time window
        userBucketRepository.getBucketsExpiringSoon(maxDaysAhead)
                .onItem().transformToUni(expiringBuckets -> {
                    if (expiringBuckets == null || expiringBuckets.isEmpty()) {
                        LOG.info("No buckets expiring within the next " + maxDaysAhead + " days");
                        return Uni.createFrom().voidItem();
                    }

                    LOG.infof("Found %d buckets expiring within %d days", expiringBuckets.size(), maxDaysAhead);
                    totalBucketsChecked.set(expiringBuckets.size());

                    // Process each bucket and check for matching templates
                    List<Uni<Void>> notificationTasks = new ArrayList<>();
                    for (ExpiringBucketInfo bucket : expiringBuckets) {
                        Uni<Void> task = processBucketExpiration(bucket, totalNotificationsSent);
                        notificationTasks.add(task);
                    }

                    // Execute all notification tasks in parallel
                    return Uni.join().all(notificationTasks).andCollectFailures()
                            .replaceWithVoid();
                })
                .subscribe().with(
                        result -> LOG.infof(
                                "Bucket expiration check completed. " +
                                "Buckets checked: %d, Notifications sent: %d, Duration: %d ms",
                                totalBucketsChecked.get(),
                                totalNotificationsSent.get(),
                                System.currentTimeMillis() - startTime),
                        error -> LOG.errorf(error, "Error during bucket expiration check")
                );
    }

    /**
     * Process a single bucket and send expiration notifications if templates match.
     *
     * @param bucket the bucket to check
     * @param totalNotificationsSent counter for tracking sent notifications
     * @return Uni that completes when processing is done
     */
    private Uni<Void> processBucketExpiration(ExpiringBucketInfo bucket, AtomicInteger totalNotificationsSent) {
        if (bucket.expiryDate() == null) {
            return Uni.createFrom().voidItem();
        }

        // Calculate days until expiration
        long daysUntilExpiration = ChronoUnit.DAYS.between(LocalDateTime.now(), bucket.expiryDate());

        if (daysUntilExpiration < 0) {
            LOG.debugf("Bucket %d has already expired, skipping notification", bucket.bucketId());
            return Uni.createFrom().voidItem();
        }

        // Parse template IDs configured for this user
        List<Long> templateIds = parseTemplateIds(bucket.notificationTemplates());
        if (templateIds.isEmpty()) {
            LOG.debugf("No notification templates configured for bucket %d", bucket.bucketId());
            return Uni.createFrom().voidItem();
        }

        // Check each template to see if it matches the current expiration window
        List<Uni<Void>> notifications = new ArrayList<>();
        for (Long templateId : templateIds) {
            Uni<Void> notification = templateCacheService.getTemplate(templateId)
                    .onItem().transformToUni(template -> {
                        if (template != null && "EXPIRY".equals(template.getType())) {
                            return checkAndSendExpirationNotification(
                                    bucket, template, templateId, daysUntilExpiration, totalNotificationsSent
                            );
                        }
                        return Uni.createFrom().voidItem();
                    });
            notifications.add(notification);
        }

        if (notifications.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Uni.join().all(notifications).andCollectFailures()
                .replaceWithVoid()
                .onFailure().invoke(error ->
                        LOG.errorf(error, "Failed to send expiration notifications for bucket: %d", bucket.bucketId()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Check if expiration notification should be sent and send it.
     *
     * @param bucket the expiring bucket
     * @param template the notification template
     * @param templateId the template ID
     * @param daysUntilExpiration days until the bucket expires
     * @param totalNotificationsSent counter for tracking sent notifications
     * @return Uni that completes when notification is sent
     */
    private Uni<Void> checkAndSendExpirationNotification(
            ExpiringBucketInfo bucket,
            ThresholdGlobalTemplates template,
            Long templateId,
            long daysUntilExpiration,
            AtomicInteger totalNotificationsSent) {

        Long templateDaysToExpire = template.getDaysToExpire();
        if (templateDaysToExpire == null) {
            return Uni.createFrom().voidItem();
        }

        // Check if the current days until expiration matches the template
        // We send notification when bucket expires in exactly N days (or less for the first time)
        if (daysUntilExpiration > templateDaysToExpire) {
            return Uni.createFrom().voidItem();
        }

        LOG.infof("Bucket %d expiring in %d days matches template %d (%d days threshold)",
                bucket.bucketId(), daysUntilExpiration, templateId, templateDaysToExpire);

        // Check if this notification was already sent to prevent duplicates
        return notificationTrackingService.isDuplicateNotification(
                bucket.bucketUser(),
                templateId,
                String.valueOf(bucket.bucketId()),
                templateDaysToExpire
        ).onItem().transformToUni(isDuplicate -> {
            if (Boolean.TRUE.equals(isDuplicate)) {
                LOG.infof("Skipping duplicate expiration notification for user=%s, templateId=%d, bucket=%d, daysToExpire=%d",
                        bucket.bucketUser(), templateId, bucket.bucketId(), templateDaysToExpire);
                return Uni.createFrom().voidItem();
            }

            // Create and send notification
            QuotaNotificationEvent event = createExpirationNotificationEvent(
                    bucket, template, templateId, daysUntilExpiration
            );

            // Mark notification as sent and publish the event
            return notificationTrackingService.markNotificationSent(
                    bucket.bucketUser(),
                    templateId,
                    String.valueOf(bucket.bucketId()),
                    templateDaysToExpire
            ).onItem().transformToUni(v -> {
                totalNotificationsSent.incrementAndGet();
                return accountProducer.produceQuotaNotificationEvent(event);
            }).onItem().invoke(() ->
                    LOG.infof("Sent expiration notification for user=%s, bucket=%d, daysUntilExpiration=%d",
                            bucket.bucketUser(), bucket.bucketId(), daysUntilExpiration)
            );
        });
    }

    /**
     * Create expiration notification event from template and bucket data.
     *
     * @param bucket the expiring bucket
     * @param template the notification template
     * @param templateId the template ID
     * @param daysUntilExpiration days until expiration
     * @return QuotaNotificationEvent
     */
    private QuotaNotificationEvent createExpirationNotificationEvent(
            ExpiringBucketInfo bucket,
            ThresholdGlobalTemplates template,
            Long templateId,
            long daysUntilExpiration) {

        String message = formatExpirationMessage(
                template.getMassage(),
                bucket.bucketUser(),
                bucket.bucketId(),
                daysUntilExpiration,
                bucket.expiryDate()
        );

        String type = daysUntilExpiration == 1
                ? "Bucket expiring in 1 day"
                : "Bucket expiring in " + daysUntilExpiration + " days";

        return new QuotaNotificationEvent(
                LocalDateTime.now(),
                message,
                bucket.bucketUser(),
                type,
                bucket.currentBalance(),
                String.valueOf(bucket.bucketId()),
                template.getDaysToExpire(),
                bucket.initialBalance(),
                templateId
        );
    }

    /**
     * Format expiration message template with actual values.
     *
     * @param template message template
     * @param username user name
     * @param bucketId bucket ID
     * @param daysUntilExpiration days until expiration
     * @param expiryDate expiration date
     * @return formatted message
     */
    private String formatExpirationMessage(
            String template,
            String username,
            Long bucketId,
            long daysUntilExpiration,
            LocalDateTime expiryDate) {

        if (template == null) {
            return String.format("Bucket %d will expire in %d days on %s",
                    bucketId, daysUntilExpiration, expiryDate);
        }

        return template
                .replace("{username}", username != null ? username : "")
                .replace("{bucketId}", String.valueOf(bucketId))
                .replace("{daysUntilExpiration}", String.valueOf(daysUntilExpiration))
                .replace("{expiryDate}", expiryDate != null ? expiryDate.toString() : "");
    }

    /**
     * Parse comma-separated template IDs from string.
     * Expected format: "1,3,5"
     *
     * @param templateIds comma-separated template IDs
     * @return list of template IDs
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
                LOG.warnf("Invalid template ID in bucket notification templates: %s", part);
            }
        }

        return ids;
    }
}
