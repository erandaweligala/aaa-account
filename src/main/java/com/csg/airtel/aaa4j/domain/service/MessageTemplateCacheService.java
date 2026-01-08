package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.MessageTemplate;
import com.csg.airtel.aaa4j.domain.model.ThresholdGlobalTemplates;
import com.csg.airtel.aaa4j.external.repository.MessageTemplateRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Startup
public class MessageTemplateCacheService {

    private static final Logger LOG = Logger.getLogger(MessageTemplateCacheService.class);
    private static final String CACHE_KEY_PREFIX = "template:";

    private final MessageTemplateRepository templateRepository;

    private final ReactiveValueCommands<String, ThresholdGlobalTemplates> valueCommands;

    private final Map<Long, ThresholdGlobalTemplates> inMemoryCache;

    @Inject
    public MessageTemplateCacheService(
            MessageTemplateRepository templateRepository,
            ReactiveRedisDataSource reactiveRedisDataSource) {
        this.templateRepository = templateRepository;
        this.valueCommands = reactiveRedisDataSource.value(String.class, ThresholdGlobalTemplates.class);
        this.inMemoryCache = new HashMap<>();
    }

    /**
     * Initialize message template cache at application startup.
     * Loads all active templates from database and caches them in Redis and in-memory.
     */
    @PostConstruct
    void initializeTemplateCache() {
        LOG.info("Initializing message template cache at application startup...");

        templateRepository.getAllActiveTemplates()
                .onItem().invoke(templates -> {
                    if (templates == null || templates.isEmpty()) {
                        LOG.warn("No active message templates found in database");
                        return;
                    }

                    LOG.infof("Loading %d active message templates into cache", templates.size());

                    for (MessageTemplate template : templates) {
                        try {
                            cacheTemplate(template);
                        } catch (Exception e) {
                            LOG.errorf(e, "Failed to cache template ID %d: %s",
                                    template.getTemplateId(), template.getTemplateName());
                        }
                    }

                    LOG.infof("Successfully loaded %d message templates into cache", templates.size());
                })
                .onFailure().invoke(error ->
                        LOG.error("Failed to initialize message template cache from database. Using fallback.", error))
                .subscribe().with(
                        result -> LOG.debug("Template cache initialization completed"),
                        error -> LOG.error("Template cache initialization failed", error)
                );
    }

    /**
     * Cache a single message template in both Redis and in-memory.
     * Only caches USAGE type templates for quota notifications.
     */
    private void cacheTemplate(MessageTemplate template) {
        if (template == null || template.getTemplateId() == null) {
            LOG.warn("Skipping null or invalid template");
            return;
        }

        // Currently only USAGE type templates are used for quota notifications
        if (!"USAGE".equals(template.getMessageType())) {
            LOG.debugf("Skipping non-USAGE template ID %d (type: %s)",
                    template.getTemplateId(), template.getMessageType());
            return;
        }

        ThresholdGlobalTemplates thresholdTemplate = template.toThresholdGlobalTemplates();
        String cacheKey = CACHE_KEY_PREFIX + template.getTemplateId();

        // Cache in Redis (fire and forget for startup performance)
        valueCommands.set(cacheKey, thresholdTemplate)
                .subscribe().with(
                        success -> LOG.debugf("Cached template ID %d in Redis: %s (%d%%)",
                                template.getTemplateId(), template.getTemplateName(), template.getQuotaPercentage()),
                        error -> LOG.warnf("Failed to cache template ID %d in Redis: %s",
                                template.getTemplateId(), error.getMessage())
                );

        // Cache in memory for fast access
        inMemoryCache.put(template.getTemplateId(), thresholdTemplate);

        LOG.debugf("Cached template ID %d in-memory: %s (%d%%)",
                template.getTemplateId(), template.getTemplateName(), template.getQuotaPercentage());
    }

    /**
     *
     * @param templateId the template ID
     * @return Uni containing ThresholdGlobalTemplates or null if not found
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 2,
            delay = 100,
            maxDuration = 10000
    )
    @Timeout(value = 10000)
    public Uni<ThresholdGlobalTemplates> getTemplate(Long templateId) {
        if (templateId == null) {
            return Uni.createFrom().nullItem();
        }

        // Fast path: check in-memory cache first
        ThresholdGlobalTemplates inMemoryTemplate = inMemoryCache.get(templateId);
        if (inMemoryTemplate != null) {
            LOG.debugf("Template ID %d found in in-memory cache", templateId);
            return Uni.createFrom().item(inMemoryTemplate);
        }

        // Fallback: check Redis cache
        String cacheKey = CACHE_KEY_PREFIX + templateId;
        LOG.debugf("Template ID %d not in memory, checking Redis", templateId);

        return valueCommands.get(cacheKey)
                .onItem().invoke(template -> {
                    if (template != null) {
                        // Update in-memory cache
                        inMemoryCache.put(templateId, template);
                        LOG.debugf("Template ID %d found in Redis and cached in-memory", templateId);
                    } else {
                        LOG.warnf("Template ID %d not found in cache", templateId);
                    }
                })
                .onFailure().invoke(error ->
                        LOG.errorf(error, "Error retrieving template ID %d from Redis", templateId))
                .onFailure().recoverWithNull();
    }

    /**
     * Get multiple templates by superTemplateId (comma-separated template IDs).
     * Optimized batch retrieval from cache.
     *
     * @param superTemplateId comma-separated string of template IDs (e.g., "1,2,3")
     * @return Uni containing Map of templateId to ThresholdGlobalTemplates
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 5000,
            successThreshold = 2
    )
    @Retry(
            maxRetries = 2,
            delay = 100,
            maxDuration = 10000
    )
    @Timeout(value = 10000)
    public Uni<Map<Long, ThresholdGlobalTemplates>> getTemplatesBySuperTemplateId(String superTemplateId) {
        if (superTemplateId == null || superTemplateId.trim().isEmpty()) {
            return Uni.createFrom().item(Collections.emptyMap());
        }

        // Parse template IDs
        List<Long> templateIds = parseTemplateIds(superTemplateId);
        if (templateIds.isEmpty()) {
            return Uni.createFrom().item(Collections.emptyMap());
        }

        LOG.debugf("Fetching %d templates for superTemplateId: %s", templateIds.size(), superTemplateId);

        // First, try to get all templates from in-memory cache
        Map<Long, ThresholdGlobalTemplates> result = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();

        for (Long templateId : templateIds) {
            ThresholdGlobalTemplates template = inMemoryCache.get(templateId);
            if (template != null) {
                result.put(templateId, template);
            } else {
                missingIds.add(templateId);
            }
        }

        // If all templates found in memory, return immediately
        if (missingIds.isEmpty()) {
            LOG.debugf("All %d templates found in in-memory cache", result.size());
            return Uni.createFrom().item(result);
        }

        LOG.debugf("%d templates found in memory, %d missing - checking Redis", result.size(), missingIds.size());

        // Fetch missing templates from Redis
        List<Uni<Void>> redisFetches = missingIds.stream()
                .map(templateId -> {
                    String cacheKey = CACHE_KEY_PREFIX + templateId;
                    return valueCommands.get(cacheKey)
                            .onItem().invoke(template -> {
                                if (template != null) {
                                    result.put(templateId, template);
                                    inMemoryCache.put(templateId, template);
                                    LOG.debugf("Template ID %d retrieved from Redis and cached in-memory", templateId);
                                } else {
                                    LOG.warnf("Template ID %d not found in Redis cache", templateId);
                                }
                            })
                            .onFailure().invoke(error ->
                                    LOG.errorf(error, "Error retrieving template ID %d from Redis", templateId))
                            .onFailure().recoverWithNull()
                            .replaceWithVoid();
                })
                .collect(Collectors.toList());

        // Wait for all Redis fetches to complete
        return Uni.join().all(redisFetches).andFailFast()
                .replaceWith(result)
                .onItem().invoke(templates ->
                        LOG.debugf("Batch retrieval completed: %d templates retrieved", templates.size()));
    }

    /**
     * Parse comma-separated template IDs from string.
     * @param templateIds comma-separated string of template IDs
     * @return List of parsed template IDs
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
                LOG.warnf("Invalid template ID in superTemplateId: %s", part);
            }
        }

        return ids;
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
     * Refresh the template cache by reloading from database.
     * Useful for runtime updates without restart.
     *
     * @return Uni that completes when refresh is done
     */
    public Uni<Void> refreshCache() {
        LOG.info("Refreshing message template cache from database...");

        return templateRepository.getAllActiveTemplates()
                .onItem().invoke(templates -> {
                    // Clear existing cache
                    inMemoryCache.clear();

                    if (templates == null || templates.isEmpty()) {
                        LOG.warn("No active templates found during refresh");
                        return;
                    }

                    // Reload templates
                    for (MessageTemplate template : templates) {
                        try {
                            cacheTemplate(template);
                        } catch (Exception e) {
                            LOG.errorf(e, "Failed to cache template ID %d during refresh",
                                    template.getTemplateId());
                        }
                    }

                    LOG.infof("Cache refresh completed: %d templates loaded", templates.size());
                })
                .onFailure().invoke(error ->
                        LOG.error("Failed to refresh message template cache", error))
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
