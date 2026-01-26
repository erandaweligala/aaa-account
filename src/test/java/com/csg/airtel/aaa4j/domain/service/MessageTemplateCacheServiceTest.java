package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.MessageTemplate;
import com.csg.airtel.aaa4j.domain.model.ThresholdGlobalTemplates;
import com.csg.airtel.aaa4j.external.repository.MessageTemplateRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
// todo pls fixed errors
@ExtendWith(MockitoExtension.class)
class MessageTemplateCacheServiceTest {

    @Mock
    private MessageTemplateRepository templateRepository;

    @Mock
    private ReactiveRedisDataSource reactiveRedisDataSource;

    @Mock
    private ReactiveValueCommands<String, ThresholdGlobalTemplates> valueCommands;

    @Mock
    private ReactiveKeyCommands<String> keyCommands;

    private MessageTemplateCacheService cacheService;

    @BeforeEach
    void setUp() {
        when(reactiveRedisDataSource.value(String.class, ThresholdGlobalTemplates.class))
            .thenReturn(valueCommands);
        when(reactiveRedisDataSource.key()).thenReturn(keyCommands);
        when(valueCommands.set(anyString(), any(ThresholdGlobalTemplates.class)))
            .thenReturn(Uni.createFrom().voidItem());

        when(templateRepository.getAllActiveTemplates())
            .thenReturn(Uni.createFrom().item(new ArrayList<>()));

        cacheService = new MessageTemplateCacheService(
            templateRepository, reactiveRedisDataSource
        );
    }

    @Test
    void testGetAllTemplates() {
        Map<Long, ThresholdGlobalTemplates> templates = cacheService.getAllTemplates();

        assertNotNull(templates);
    }

    @Test
    void testGetCacheSize() {
        int size = cacheService.getCacheSize();

        assertTrue(size >= 0);
    }

    @Test
    void testRefreshCache_Success() {
        List<MessageTemplate> mockTemplates = createMockTemplates();
        when(templateRepository.getAllActiveTemplates())
            .thenReturn(Uni.createFrom().item(mockTemplates));

        cacheService.refreshCache()
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(templateRepository).getAllActiveTemplates();
    }

    @Test
    void testRefreshCache_EmptyTemplates() {
        when(templateRepository.getAllActiveTemplates())
            .thenReturn(Uni.createFrom().item(new ArrayList<>()));

        cacheService.refreshCache()
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem();

        verify(templateRepository).getAllActiveTemplates();
    }

    @Test
    void testGetTemplatesBySuperTemplateId_Null() {
        List<ThresholdGlobalTemplates> result = cacheService
            .getTemplatesBySuperTemplateId(null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetTemplatesBySuperTemplateId_FromRedis() {
        Long superTemplateId = 1L;
        when(keyCommands.keys(anyString()))
            .thenReturn(Uni.createFrom().item(List.of("1:100")));
        when(valueCommands.get(anyString()))
            .thenReturn(Uni.createFrom().item(createMockThresholdTemplate()));

        List<ThresholdGlobalTemplates> result = cacheService
            .getTemplatesBySuperTemplateId(superTemplateId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
    }

    private List<MessageTemplate> createMockTemplates() {
        List<MessageTemplate> templates = new ArrayList<>();
        MessageTemplate template = new MessageTemplate();
        template.setTemplateId(100L);
        template.setSuperTemplateId(1L);
        template.setMessageType("USAGE");
        template.setQuotaPercentage(80);
        template.setMessageContent("Test message");
        templates.add(template);
        return templates;
    }

    private ThresholdGlobalTemplates createMockThresholdTemplate() {
        return new ThresholdGlobalTemplates(
            1L, 100L, 80L, "Test message", new String[]{"MOCN"}
        );
    }
}
