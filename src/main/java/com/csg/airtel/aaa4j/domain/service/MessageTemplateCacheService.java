package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.MessageTemplate;
import com.csg.airtel.aaa4j.domain.model.ThresholdGlobalTemplates;
import com.csg.airtel.aaa4j.external.repository.MessageTemplateRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@ApplicationScoped
@Startup
public class MessageTemplateCacheService {

    private static final Logger LOG = Logger.getLogger(MessageTemplateCacheService.class);
    private static final String CLASS_NAME = MessageTemplateCacheService.class.getSimpleName();

    private final MessageTemplateRepository templateRepository;

    private final ReactiveValueCommands<String, ThresholdGlobalTemplates> valueCommands;

    private final ReactiveKeyCommands<String> keyCommands;

    private final Map<Long, ThresholdGlobalTemplates> inMemoryCache;

    @Inject
    public MessageTemplateCacheService(
            MessageTemplateRepository templateRepository,
            ReactiveRedisDataSource reactiveRedisDataSource) {
        this.templateRepository = templateRepository;
        this.valueCommands = reactiveRedisDataSource.value(String.class, ThresholdGlobalTemplates.class);
        this.keyCommands = reactiveRedisDataSource.key();
        this.inMemoryCache = new HashMap<>();
    }

    /**
     * Initialize message template cache at application startup.
     * Loads all active templates from database and caches them in Redis and in-memory.
     */
    @PostConstruct
    void initializeTemplateCache() {
        LoggingUtil.logInfo(LOG, CLASS_NAME, "initializeTemplateCache", "Initializing message template cache at application startup...");

        templateRepository.getAllActiveTemplates()
                .onItem().invoke(templates -> {
                    if (templates == null || templates.isEmpty()) {
                        LoggingUtil.logWarn(LOG, CLASS_NAME, "initializeTemplateCache", "No active message templates found in database");
                        return;
                    }

                    LoggingUtil.logInfo(LOG, CLASS_NAME, "initializeTemplateCache", "Loading %d active message templates into cache", templates.size());

                    for (MessageTemplate template : templates) {
                        try {
                            cacheTemplate(template);
                        } catch (Exception e) {
                            LoggingUtil.logError(LOG, CLASS_NAME, "initializeTemplateCache", e, "Failed to cache template ID %d: %s",
                                    template.getTemplateId(), template.getTemplateName());
                        }
                    }

                    LoggingUtil.logInfo(LOG, CLASS_NAME, "initializeTemplateCache", "Successfully loaded %d message templates into cache", templates.size());
                })
                .onFailure().invoke(error ->
                        LoggingUtil.logError(LOG, CLASS_NAME, "initializeTemplateCache", error, "Failed to initialize message template cache from database. Using fallback."))
                .subscribe().with(
                        result -> LoggingUtil.logDebug(LOG, CLASS_NAME, "initializeTemplateCache", "Template cache initialization completed"),
                        error -> LoggingUtil.logError(LOG, CLASS_NAME, "initializeTemplateCache", error, "Template cache initialization failed")
                );
    }

    /**
     * Cache a single message template in both Redis and in-memory.
     * Only caches USAGE type templates for quota notifications.
     */
    private void cacheTemplate(MessageTemplate template) {
        if (template == null || template.getTemplateId() == null) {
            LoggingUtil.logWarn(LOG, CLASS_NAME, "cacheTemplate", "Skipping null or invalid template");
            return;
        }

        // Currently only USAGE type templates are used for quota notifications
        if (!"USAGE".equals(template.getMessageType())) {
            LoggingUtil.logDebug(LOG, CLASS_NAME, "cacheTemplate", "Skipping non-USAGE template ID %d (type: %s)",
                    template.getTemplateId(), template.getMessageType());
            return;
        }

        ThresholdGlobalTemplates thresholdTemplate = template.toThresholdGlobalTemplates();
        String cacheKey = template.getSuperTemplateId() +":"+ template.getTemplateId();

        // Cache in Redis (fire and forget for startup performance)
        valueCommands.set(cacheKey, thresholdTemplate)
                .subscribe().with(
                        success -> LoggingUtil.logDebug(LOG, CLASS_NAME, "cacheTemplate", "Cached template ID %d in Redis: %s (%d%%)",
                                template.getTemplateId(), template.getTemplateName(), template.getQuotaPercentage()),
                        error -> LoggingUtil.logWarn(LOG, CLASS_NAME, "cacheTemplate", "Failed to cache template ID %d in Redis: %s",
                                template.getTemplateId(), error.getMessage())
                );

        // Cache in memory for fast access
        inMemoryCache.put(template.getSuperTemplateId() + template.getTemplateId(), thresholdTemplate);

        LoggingUtil.logDebug(LOG, CLASS_NAME, "cacheTemplate", "Cached template ID %d in-memory: %s (%d%%)",
                template.getTemplateId(), template.getTemplateName(), template.getQuotaPercentage());
    }

    /**
     * Get all cached templates as a map.
     * Returns in-memory cache for performance.
     *
     * @return Map of templateId to ThresholdGlobalTemplates
     */
    public Map<Long, ThresholdGlobalTemplates> getAllTemplates() {
        return new HashMap<>(inMemoryCache);
    }

    /**
     * Get all templates matching a specific superTemplateId prefix.
     * This retrieves all templates that belong to a super template group.
     * Falls back to Redis cache if not found in-memory.
     *
     * @param superTemplateId the super template ID to filter by
     * @return List of ThresholdGlobalTemplates matching the superTemplateId
     */
    public Uni<List<ThresholdGlobalTemplates>> getTemplatesBySuperTemplateId(Long superTemplateId) {
        if (superTemplateId == null) {
            return Uni.createFrom().item(Collections.emptyList());
        }

        LoggingUtil.logDebug(LOG, CLASS_NAME, "getTemplatesBySuperTemplateId", "Fetching all templates for superTemplateId: %d", superTemplateId);

        // Fast path: Filter in-memory cache for templates with matching superTemplateId prefix
        // Templates are keyed as: superTemplateId + templateId (concatenated Long values)
        List<ThresholdGlobalTemplates> matchingTemplates = inMemoryCache.entrySet().stream()
                .filter(entry -> {
                    // Check if the key starts with the superTemplateId
                    // Since keys are composite (superTemplateId + templateId), we need to check the prefix
                    String keyStr = String.valueOf(entry.getKey());
                    String superIdStr = String.valueOf(superTemplateId);
                    return keyStr.startsWith(superIdStr);
                })
                .map(Map.Entry::getValue)
                .toList();

        LoggingUtil.logDebug(LOG, CLASS_NAME, "getTemplatesBySuperTemplateId", "Found %d templates in-memory for superTemplateId: %d", matchingTemplates.size(), superTemplateId);

        // If found in memory, return immediately
        if (!matchingTemplates.isEmpty()) {
            return Uni.createFrom().item(matchingTemplates);
        }

        LoggingUtil.logDebug(LOG, CLASS_NAME, "getTemplatesBySuperTemplateId", "No templates found in-memory for superTemplateId: %d, checking Redis", superTemplateId);


        String pattern = superTemplateId + ":*";

        return keyCommands.keys(pattern)
                .onItem().transformToUni(keys -> {
                    if (keys == null || keys.isEmpty()) {
                        LoggingUtil.logWarn(LOG, CLASS_NAME, "getTemplatesBySuperTemplateId", "No templates found in Redis for superTemplateId: %d", superTemplateId);
                        return Uni.createFrom().item(Collections.<ThresholdGlobalTemplates>emptyList());
                    }

                    LoggingUtil.logDebug(LOG, CLASS_NAME, "getTemplatesBySuperTemplateId", "Found %d template keys in Redis for superTemplateId: %d", keys.size(), superTemplateId);

                    // Fetch all templates from Redis
                    List<Uni<ThresholdGlobalTemplates>> fetches = keys.stream()
                            .map(key -> valueCommands.get(key)
                                    .onItem().invoke(template -> {
                                        if (template != null) {
                                            // Cache in memory for future lookups
                                            Long compositeKey = extractCompositeKey(key);
                                            if (compositeKey != null) {
                                                inMemoryCache.put(compositeKey, template);
                                                LoggingUtil.logDebug(LOG, CLASS_NAME, "getTemplatesBySuperTemplateId", "Cached template from Redis to in-memory: %s", key);
                                            }
                                        }
                                    })
                                    .onFailure().invoke(error ->
                                            LoggingUtil.logError(LOG, CLASS_NAME, "getTemplatesBySuperTemplateId", error, "Error retrieving template from Redis: %s", key))
                                    .onFailure().recoverWithNull())
                            .toList();

                    // Combine all fetch operations
                    return Uni.combine().all().unis(fetches).with(results ->
                            results.stream()
                                    .filter(ThresholdGlobalTemplates.class::isInstance)
                                    .map(ThresholdGlobalTemplates.class::cast)
                                    .toList()
                    );
                })
                .onFailure().invoke(error ->
                        LoggingUtil.logError(LOG, CLASS_NAME, "getTemplatesBySuperTemplateId", error, "Error retrieving templates from Redis for superTemplateId: %d", superTemplateId))
                .onFailure().recoverWithItem(Collections.emptyList());
    }

    /**
     * Extract composite key (superTemplateId + templateId) from Redis key format.
     * Redis key format: "superTemplateId:templateId"
     * Composite key format: superTemplateId concatenated with templateId
     *
     * @param redisKey the Redis key (e.g., "1234:5678")
     * @return composite key as Long (e.g., 12345678) or null if invalid
     */
    private Long extractCompositeKey(String redisKey) {
        try {
            // Remove any prefix if exists and get the superTemplateId:templateId part
            String keyPart = redisKey.contains(":") ? redisKey : null;
            if (keyPart == null) {
                return null;
            }

            String[] parts = keyPart.split(":");
            if (parts.length >= 2) {
                // Reconstruct the composite key: superTemplateId + templateId
                Long superTemplateId = Long.parseLong(parts[0]);
                Long templateId = Long.parseLong(parts[1]);
                return Long.parseLong(superTemplateId.toString() + templateId.toString());
            }
        } catch (NumberFormatException e) {
            LoggingUtil.logWarn(LOG, CLASS_NAME, "extractCompositeKey", "Failed to extract composite key from Redis key: %s", redisKey);
        }
        return null;
    }

    /**
     * Refresh the template cache by reloading from database.
     * Useful for runtime updates without restart.
     *
     * @return Uni that completes when refresh is done
     */
    public Uni<Void> refreshCache() {
        LoggingUtil.logInfo(LOG, CLASS_NAME, "refreshCache", "Refreshing message template cache from database...");

        return templateRepository.getAllActiveTemplates()
                .onItem().invoke(templates -> {
                    // Clear existing cache
                    inMemoryCache.clear();

                    if (templates == null || templates.isEmpty()) {
                        LoggingUtil.logWarn(LOG, CLASS_NAME, "refreshCache", "No active templates found during refresh");
                        return;
                    }

                    // Reload templates
                    for (MessageTemplate template : templates) {
                        try {
                            cacheTemplate(template);
                        } catch (Exception e) {
                            LoggingUtil.logError(LOG, CLASS_NAME, "refreshCache", e, "Failed to cache template ID %d during refresh",
                                    template.getTemplateId());
                        }
                    }

                    LoggingUtil.logInfo(LOG, CLASS_NAME, "refreshCache", "Cache refresh completed: %d templates loaded", templates.size());
                })
                .onFailure().invoke(error ->
                        LoggingUtil.logError(LOG, CLASS_NAME, "refreshCache", error, "Failed to refresh message template cache"))
                .replaceWithVoid();
    }

    /**
     * Get the count of cached templates.
     *
     * @return number of templates in cache
     */
    public int getCacheSize() {
        return inMemoryCache.size();
    }
}
