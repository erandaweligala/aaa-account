package com.csg.airtel.aaa4j.domain.repository;

import com.csg.airtel.aaa4j.domain.model.UserThresholdConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class UserThresholdRepositoryTest {

    @Inject
    UserThresholdRepository repository;

    private static final String TEST_USER_ID = "testuser123";
    private static final String TEST_BUCKET_ID = "bucket-456";
    private static final List<Integer> TEST_THRESHOLDS = Arrays.asList(60, 70, 80);

    @BeforeEach
    void cleanup() {
        // Clean up test data before each test
        repository.deleteUserThresholdConfig(TEST_USER_ID, TEST_BUCKET_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();
    }

    @Test
    void testSaveAndRetrieveUserThresholdConfig() {
        // Create test config
        UserThresholdConfig config = UserThresholdConfig.builder()
                .userId(TEST_USER_ID)
                .bucketId(TEST_BUCKET_ID)
                .thresholds(TEST_THRESHOLDS)
                .enabled(true)
                .cacheTtlSeconds(300)
                .build();

        // Save config
        repository.saveUserThresholdConfig(config)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Retrieve config
        UserThresholdConfig retrieved = repository.getUserThresholdConfig(TEST_USER_ID, TEST_BUCKET_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        // Verify
        assertNotNull(retrieved);
        assertEquals(TEST_USER_ID, retrieved.getUserId());
        assertEquals(TEST_BUCKET_ID, retrieved.getBucketId());
        assertEquals(TEST_THRESHOLDS, retrieved.getThresholds());
        assertTrue(retrieved.isEnabled());
    }

    @Test
    void testGetNonExistentConfig() {
        // Try to retrieve non-existent config
        UserThresholdConfig retrieved = repository.getUserThresholdConfig("nonexistent", "bucket")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        // Should return null
        assertNull(retrieved);
    }

    @Test
    void testMarkThresholdNotified() {
        // Mark threshold as notified (creates new config if doesn't exist)
        repository.markThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Check if threshold is marked as notified
        Boolean isNotified = repository.isThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertTrue(isNotified);

        // Check another threshold should not be notified
        Boolean isNotified70 = repository.isThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 70)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertFalse(isNotified70);
    }

    @Test
    void testResetThresholdNotification() {
        // Mark threshold as notified
        repository.markThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Verify it's notified
        Boolean isNotified = repository.isThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertTrue(isNotified);

        // Reset notification
        repository.resetThresholdNotification(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Verify it's reset
        Boolean isNotifiedAfterReset = repository.isThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertFalse(isNotifiedAfterReset);
    }

    @Test
    void testResetAllNotifications() {
        // Mark multiple thresholds as notified
        repository.markThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        repository.markThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 70)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Verify they're notified
        assertTrue(repository.isThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().getItem());
        assertTrue(repository.isThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 70)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().getItem());

        // Reset all notifications
        repository.resetAllNotifications(TEST_USER_ID, TEST_BUCKET_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Verify they're all reset
        assertFalse(repository.isThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 60)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().getItem());
        assertFalse(repository.isThresholdNotified(TEST_USER_ID, TEST_BUCKET_ID, 70)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().getItem());
    }

    @Test
    void testUpdateUserThresholds() {
        List<Integer> newThresholds = Arrays.asList(50, 75, 90);

        // Update thresholds
        repository.updateUserThresholds(TEST_USER_ID, TEST_BUCKET_ID, newThresholds)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Retrieve and verify
        UserThresholdConfig retrieved = repository.getUserThresholdConfig(TEST_USER_ID, TEST_BUCKET_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(retrieved);
        assertEquals(newThresholds, retrieved.getThresholds());
    }

    @Test
    void testDeleteUserThresholdConfig() {
        // Create and save config
        UserThresholdConfig config = UserThresholdConfig.builder()
                .userId(TEST_USER_ID)
                .bucketId(TEST_BUCKET_ID)
                .thresholds(TEST_THRESHOLDS)
                .enabled(true)
                .build();

        repository.saveUserThresholdConfig(config)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Verify it exists
        UserThresholdConfig retrieved = repository.getUserThresholdConfig(TEST_USER_ID, TEST_BUCKET_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertNotNull(retrieved);

        // Delete config
        repository.deleteUserThresholdConfig(TEST_USER_ID, TEST_BUCKET_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Verify it's deleted
        UserThresholdConfig afterDelete = repository.getUserThresholdConfig(TEST_USER_ID, TEST_BUCKET_ID)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertNull(afterDelete);
    }
}
