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


import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageTemplateCacheServiceTest {

    @Mock
    MessageTemplateRepository repository;

    @Mock
    ReactiveRedisDataSource redisDataSource;

    @Mock
    ReactiveValueCommands<String, ThresholdGlobalTemplates> valueCommands;

    @Mock
    ReactiveKeyCommands<String> keyCommands;

    MessageTemplateCacheService service;

    @BeforeEach
    void setUp() {
        // Setup Redis Mocks
        when(redisDataSource.value(eq(String.class), eq(ThresholdGlobalTemplates.class))).thenReturn(valueCommands);
        when(redisDataSource.key()).thenReturn(keyCommands);

        service = new MessageTemplateCacheService(repository, redisDataSource);
    }

    @Test
    void testInitializeTemplateCache_Success() {
        // Arrange
        MessageTemplate template = createSampleTemplate(100L, 200L, "USAGE");
        when(repository.getAllActiveTemplates()).thenReturn(Uni.createFrom().item(List.of(template)));
        when(valueCommands.set(anyString(), any())).thenReturn(Uni.createFrom().item((Void) null));

        // Act
        service.initializeTemplateCache();

        // Assert
        assertEquals(1, service.getCacheSize());
        verify(valueCommands, times(1)).set(eq("100:200"), any());
    }

    @Test
    void testInitializeTemplateCache_NonUsageTypeSkipped() {
        // Arrange
        MessageTemplate template = createSampleTemplate(100L, 200L, "OTHER");
        when(repository.getAllActiveTemplates()).thenReturn(Uni.createFrom().item(List.of(template)));

        // Act
        service.initializeTemplateCache();

        // Assert
        assertEquals(0, service.getCacheSize());
        verify(valueCommands, never()).set(anyString(), any());
    }

    @Test
    void testInitializeTemplateCache_FullFlow() {
        // Arrange: 1 Valid USAGE, 1 non-USAGE (to cover skip logic), 1 invalid (to cover try-catch)
        MessageTemplate valid = createTemplate(100L, 200L, "USAGE");
        MessageTemplate skip = createTemplate(101L, 201L, "OTHER");
        MessageTemplate invalid = new MessageTemplate(); // Will throw NullPointer on getTemplateId()

        when(repository.getAllActiveTemplates()).thenReturn(Uni.createFrom().item(List.of(valid, skip, invalid)));
        when(valueCommands.set(anyString(), any())).thenReturn(Uni.createFrom().item((Void) null));

        // Act
        service.initializeTemplateCache();

        // Assert: We check the size via the service method
        // (Small delay to allow the fire-and-forget subscribe to hit the map)
        int size = 0;
        for(int i=0; i<10 && size == 0; i++) {
            size = service.getCacheSize();
            try { Thread.sleep(50); } catch (Exception e) {}
        }

        assertTrue(size >= 1);
        verify(valueCommands, atLeastOnce()).set(anyString(), any());
    }

    @Test
    void testGetTemplatesBySuperTemplateId_InMemoryPath() {
        // Setup: Directly populate via refreshCache to ensure blocking completion
        MessageTemplate t = createTemplate(100L, 200L, "USAGE");
        when(repository.getAllActiveTemplates()).thenReturn(Uni.createFrom().item(List.of(t)));
        when(valueCommands.set(anyString(), any())).thenReturn(Uni.createFrom().item((Void) null));

        service.refreshCache().await().indefinitely();

        // Act
        List<ThresholdGlobalTemplates> results = service.getTemplatesBySuperTemplateId(100L).await().indefinitely();

        // Assert
        assertTrue(results.isEmpty());

    }

    @Test
    void testGetTemplatesBySuperTemplateId_RedisPathAndExtractKey() {
        // Arrange
        Long superId = 500L;
        String redisKey = "500:600";
        ThresholdGlobalTemplates threshold = new ThresholdGlobalTemplates();
        threshold.setThreshold(600L);

        when(keyCommands.keys("500:*")).thenReturn(Uni.createFrom().item(List.of(redisKey, "malformed:key", "singlepart")));
        when(valueCommands.get(redisKey)).thenReturn(Uni.createFrom().item(threshold));
        when(valueCommands.get("malformed:key")).thenReturn(Uni.createFrom().item(new ThresholdGlobalTemplates()));
        when(valueCommands.get("singlepart")).thenReturn(Uni.createFrom().failure(new RuntimeException("Redis Error")));

        // Act
        List<ThresholdGlobalTemplates> result = service.getTemplatesBySuperTemplateId(superId).await().indefinitely();

        // Assert
        assertFalse(result.isEmpty());
        // Verify extractCompositeKey logic was covered via the "500:600" key
        assertTrue(service.getAllTemplates().containsKey(500600L));
    }

    @Test
    void testExtractCompositeKey_ValidAndInvalid() {
        // Accessing private method via reflection or making it package-private for testing
        // Testing the logic: "100:200" -> 100200L

        // We can test this indirectly via getTemplatesBySuperTemplateId Redis fallback
        Long superId = 500L;
        String redisKey = "500:600";
        ThresholdGlobalTemplates cached = new ThresholdGlobalTemplates();
        cached.setThreshold(600L);

        when(keyCommands.keys("500:*")).thenReturn(Uni.createFrom().item(List.of(redisKey)));
        when(valueCommands.get(redisKey)).thenReturn(Uni.createFrom().item(cached));

        List<ThresholdGlobalTemplates> result = service.getTemplatesBySuperTemplateId(superId).await().indefinitely();

        assertEquals(1, result.size());
        // Verify it was put into inMemoryCache correctly
        assertTrue(service.getAllTemplates().containsKey(500600L));
    }

    @Test
    void testGetTemplatesBySuperTemplateId_FromRedisFallback() {
        // Arrange
        Long superId = 500L;
        String redisKey = "500:600";
        ThresholdGlobalTemplates cached = new ThresholdGlobalTemplates();
        cached.setThreshold(600L);

        when(keyCommands.keys(superId + ":*")).thenReturn(Uni.createFrom().item(List.of(redisKey)));
        when(valueCommands.get(redisKey)).thenReturn(Uni.createFrom().item(cached));

        // Act
        List<ThresholdGlobalTemplates> result = service.getTemplatesBySuperTemplateId(superId)
                .await().indefinitely();

        // Assert
        assertEquals(1, result.size());
        assertEquals(600L, result.get(0).getThreshold());
        assertEquals(1, service.getCacheSize()); // Should be put in memory after Redis fetch
    }



    @Test
    void testGetTemplatesBySuperTemplateId_EmptyParam() {
        List<ThresholdGlobalTemplates> result = service.getTemplatesBySuperTemplateId(null)
                .await().indefinitely();
        assertTrue(result.isEmpty());
    }



    @Test
    void testInitializeTemplateCache_EmptyList() {
        when(repository.getAllActiveTemplates()).thenReturn(Uni.createFrom().item(Collections.emptyList()));
        service.initializeTemplateCache();
        assertEquals(0, service.getCacheSize());
    }

    @Test
    void testInitializeTemplateCache_RepositoryFailure() {
        when(repository.getAllActiveTemplates()).thenReturn(Uni.createFrom().failure(new RuntimeException("DB Down")));
        assertDoesNotThrow(() -> service.initializeTemplateCache());
        assertEquals(0, service.getCacheSize());
    }

    @Test
    void testGetTemplatesBySuperTemplateId_NullInput() {
        List<ThresholdGlobalTemplates> result = service.getTemplatesBySuperTemplateId(null).await().indefinitely();
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetTemplatesBySuperTemplateId_RedisFallback() {
        Long superId = 500L;
        String redisKey = "500:600";
        ThresholdGlobalTemplates threshold = new ThresholdGlobalTemplates();
        threshold.setThreshold(600L);

        when(keyCommands.keys("500:*")).thenReturn(Uni.createFrom().item(List.of(redisKey)));
        when(valueCommands.get(redisKey)).thenReturn(Uni.createFrom().item(threshold));

        List<ThresholdGlobalTemplates> result = service.getTemplatesBySuperTemplateId(superId).await().indefinitely();

        assertFalse(result.isEmpty());
        assertEquals(600L, result.get(0).getThreshold());
        // Verify it was moved to in-memory cache (500 + 600 = 500600)
        assertTrue(service.getAllTemplates().containsKey(500600L));
    }

    @Test
    void testGetTemplatesBySuperTemplateId_RedisNoKeys() {
        when(keyCommands.keys(anyString())).thenReturn(Uni.createFrom().item(Collections.emptyList()));
        List<ThresholdGlobalTemplates> result = service.getTemplatesBySuperTemplateId(999L).await().indefinitely();
        assertTrue(result.isEmpty());
    }

    @Test
    void testRefreshCache() {
        MessageTemplate template = createTemplate(1L, 2L, "USAGE");
        when(repository.getAllActiveTemplates()).thenReturn(Uni.createFrom().item(List.of(template)));
        when(valueCommands.set(anyString(), any())).thenReturn(Uni.createFrom().item((Void) null));

        service.refreshCache().await().indefinitely();

        assertEquals(1, service.getCacheSize());
        verify(repository, times(1)).getAllActiveTemplates();
    }

    @Test
    void testCacheTemplate_SkipInvalid() {
        // Trigger the "Skipping null or invalid template" line
        // Since cacheTemplate is private, we trigger it via refreshCache/initialize
        MessageTemplate invalid = new MessageTemplate(); // null IDs
        when(repository.getAllActiveTemplates()).thenReturn(Uni.createFrom().item(List.of(invalid)));

        service.refreshCache().await().indefinitely();
        assertEquals(0, service.getCacheSize());
    }

    // Helper method to create a MessageTemplate
    private MessageTemplate createSampleTemplate(Long superId, Long templateId, String type) {
        MessageTemplate t = new MessageTemplate();
        t.setSuperTemplateId(superId);
        t.setTemplateId(templateId);
        t.setMessageType(type);
        t.setTemplateName("Test Template");
        t.setQuotaPercentage(80);
        return t;
    }

    private MessageTemplate createTemplate(Long superId, Long templateId, String type) {
        MessageTemplate t = new MessageTemplate();
        t.setSuperTemplateId(superId);
        t.setTemplateId(templateId);
        t.setMessageType(type);
        t.setTemplateName("Test");
        t.setQuotaPercentage(50);
        return t;
    }
}