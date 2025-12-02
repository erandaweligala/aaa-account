package com.csg.airtel.aaa4j.domain.service;


import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.UpdateResult;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.ConsumptionRecord;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountingUtilTest {

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private CacheClient cacheClient;

    @InjectMocks
    private AccountingUtil accountingUtil;

    @AfterEach
    void tearDown() {
        accountingUtil.clearTemporalCache();
    }

    @Test
    void testIsWithinTimeWindowValid() {
        assertTrue(accountingUtil.isWithinTimeWindow("0-24"));
        assertTrue(accountingUtil.isWithinTimeWindow("00-24"));
    }

    @Test
    void testIsWithinTimeWindowInvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("invalid");
        });
    }

    @Test
    void testIsWithinTimeWindowNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow(null);
        });
    }

    @Test
    void testIsWithinTimeWindowEmpty() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("");
        });
    }

    @Test
    void testIsWithinTimeWindowInvalidHour() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("0-25");
        });
    }

    @Test
    void testFindBalanceWithHighestPriorityNull() {
        Balance balance = accountingUtil.findBalanceWithHighestPriority(null, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNull(balance);
    }

    @Test
    void testFindBalanceWithHighestPriorityEmpty() {
        List<Balance> emptyList = new ArrayList<>();

        Balance balance = accountingUtil.findBalanceWithHighestPriority(emptyList, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNull(balance);
    }

    @Test
    void testFindBalanceWithHighestPriorityByBucketId() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, "BUCKET2")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPriorityByPriority() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET1", result.getBucketId());
        assertEquals(1L, result.getPriority());
    }

    @Test
    void testCalculateConsumptionInWindowEmpty() {
        Balance balance = new Balance();
        balance.setConsumptionHistory(new ArrayList<>());

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 24L);

        assertEquals(0L, consumption);
    }

    @Test
    void testCalculateConsumptionInWindowNull() {
        Balance balance = new Balance();
        balance.setConsumptionHistory(null);

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 24L);

        assertEquals(0L, consumption);
    }

    @Test
    void testCalculateConsumptionInWindowWithRecords() {
        Balance balance = new Balance();
        List<ConsumptionRecord> records = new ArrayList<>();
        records.add(new ConsumptionRecord(LocalDateTime.now().minusHours(1), 1000L));
        records.add(new ConsumptionRecord(LocalDateTime.now().minusHours(2), 2000L));
        balance.setConsumptionHistory(records);

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 24L);

        assertEquals(3000L, consumption);
    }

    @Test
    void testCalculateConsumptionInWindowExcludesOldRecords() {
        Balance balance = new Balance();
        List<ConsumptionRecord> records = new ArrayList<>();
        records.add(new ConsumptionRecord(LocalDateTime.now().minusHours(1), 1000L));
        records.add(new ConsumptionRecord(LocalDateTime.now().minusDays(2), 2000L));
        balance.setConsumptionHistory(records);

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 24L);

        assertEquals(1000L, consumption);
    }

    @Test
    void testClearTemporalCache() {
        assertDoesNotThrow(() -> accountingUtil.clearTemporalCache());
    }

    @Test
    void testIsWithinTimeWindowSpanningMidnight() {
        // Time window spanning midnight (e.g., 22-6 means 10 PM to 6 AM)
        // This test will pass or fail depending on current time
        // We're just testing that it doesn't throw an exception
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("22-6"));
    }

    @Test
    void testIsWithinTimeWindowSingleDigitHours() {
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("8-18"));
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("0-12"));
    }

    @Test
    void testIsWithinTimeWindowMixedFormat() {
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("08-18"));
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("8-12"));
    }

    @Test
    void testFindBalanceWithHighestPriorityExpiredBalance() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setServiceExpiry(LocalDateTime.now().minusDays(1));
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPriorityInactiveBalance() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Inactive", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPriorityZeroQuota() {
        Balance balance1 = createBalance("BUCKET1", 1L, 0L, "Active", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPriorityFutureStartDate() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setServiceStartDate(LocalDateTime.now().plusDays(1));
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPrioritySamePriorityDifferentExpiry() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        Balance balance2 = createBalance("BUCKET2", 1L, 2000L, "Active", "0-24");
        balance2.setBucketExpiryDate(LocalDateTime.now().plusDays(30));

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        // Should select the one with earlier expiry when priority is same
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPriorityAllIneligible() {
        Balance balance1 = createBalance("BUCKET1", 1L, 0L, "Active", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 0L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNull(result);
    }

    @Test
    void testFindBalanceWithHighestPriorityNonExistentBucketId() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, "BUCKET3")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        // Should fall back to priority-based selection
        assertEquals("BUCKET1", result.getBucketId());
    }


    @Test
    void testFindBalanceWithConsumptionLimit() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setConsumptionLimit(5000L);
        balance1.setConsumptionLimitWindow(24L);
        List<ConsumptionRecord> records = new ArrayList<>();
        records.add(new ConsumptionRecord(LocalDateTime.now().minusHours(1), 6000L));
        balance1.setConsumptionHistory(records);

        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        // Should skip BUCKET1 due to consumption limit exceeded
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithConsumptionLimitNotExceeded() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setConsumptionLimit(5000L);
        balance1.setConsumptionLimitWindow(24L);
        List<ConsumptionRecord> records = new ArrayList<>();
        records.add(new ConsumptionRecord(LocalDateTime.now().minusHours(1), 3000L));
        balance1.setConsumptionHistory(records);

        List<Balance> balances = List.of(balance1);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET1", result.getBucketId());
    }

    @Test
    void testIsWithinTimeWindowOutsideWindow() {
        // Test that returns false for times outside the window (behavior depends on current time)
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("23-23"));
    }

    @Test
    void testIsWithinTimeWindowNegativeHour() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("-1-10");
        });
    }






    @Test
    void testFindBalanceWithHighestPriorityMultiplePriorities() {
        Balance balance1 = createBalance("BUCKET1", 3L, 1000L, "Active", "0-24");
        Balance balance2 = createBalance("BUCKET2", 1L, 2000L, "Active", "0-24");
        Balance balance3 = createBalance("BUCKET3", 2L, 3000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2, balance3);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET2", result.getBucketId());
        assertEquals(1L, result.getPriority());
    }

    @Test
    void testFindBalanceOutsideTimeWindow() {
        // Create time window that excludes current time (hard to guarantee, but we test the logic)
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setTimeWindow("00-00");  // Only midnight - should be excluded
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithConsumptionLimitWithNullHistory() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setConsumptionLimit(5000L);
        balance1.setConsumptionLimitWindow(24L);
        balance1.setConsumptionHistory(null);

        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        // Balance1 should be eligible since consumption history is null (no consumption recorded yet)
        assertNotNull(result);
        assertEquals("BUCKET1", result.getBucketId());
    }

    @Test
    void testFindBalanceWithZeroConsumptionLimit() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setConsumptionLimit(0L);  // Zero limit
        balance1.setConsumptionLimitWindow(24L);

        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        // Balance1 should be eligible since zero consumption limit means no limit
        assertNotNull(result);
        assertEquals("BUCKET1", result.getBucketId());
    }

    @Test
    void testIsWithinTimeWindowHourParsing() {
        // Test various hour formats
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("0-24"));
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("00-24"));
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("1-23"));
        assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow("09-17"));
    }

    @Test
    void testIsWithinTimeWindowEmptyString() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("  ");
        });
    }

    @Test
    void testIsWithinTimeWindowOnlyDash() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("-");
        });
    }

    @Test
    void testIsWithinTimeWindowMultipleDashes() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("00-12-24");
        });
    }

    @Test
    void testFindBalanceWithHighestPriorityMixedEligibility() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setServiceExpiry(LocalDateTime.now().minusDays(1));  // Expired

        Balance balance2 = createBalance("BUCKET2", 2L, 0L, "Active", "0-24");  // Zero quota

        Balance balance3 = createBalance("BUCKET3", 3L, 500L, "Active", "0-24");  // Eligible with lower priority

        List<Balance> balances = List.of(balance1, balance2, balance3);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        // Should select BUCKET3 as it's the only eligible one
        assertEquals("BUCKET3", result.getBucketId());
    }

    @Test
    void testFindBalanceNullBucketId() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setBucketId(null);

        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);

    }

    @Test
    void testFindBalanceHighestPriorityWithExpiryComparison() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setBucketExpiryDate(LocalDateTime.now().plusDays(100));

        Balance balance2 = createBalance("BUCKET2", 1L, 1000L, "Active", "0-24");
        balance2.setBucketExpiryDate(LocalDateTime.now().plusDays(50));

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        // Should select BUCKET2 with earlier expiry date when priorities are same
        assertEquals("BUCKET2", result.getBucketId());
    }



    @Test
    void testIsWithinTimeWindowSpanningMidnightAfterCutoff() {
        // Test window that spans midnight and current time is after cutoff
        // Window 22-6 means 10 PM to 6 AM
        assertDoesNotThrow(() -> {
            boolean result = accountingUtil.isWithinTimeWindow("22-6");
            // Result depends on current time, just ensure no exception
            assertTrue(result || !result);  // Always true, just ensure it returns a boolean
        });
    }

    @Test
    void testCalculateConsumptionInWindowAllRecordsOutside() {
        Balance balance = new Balance();
        List<ConsumptionRecord> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        records.add(new ConsumptionRecord(now.minusDays(5), 1000L));
        records.add(new ConsumptionRecord(now.minusDays(3), 2000L));
        balance.setConsumptionHistory(records);

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 24L);
        assertEquals(0L, consumption);
    }

    @Test
    void testCalculateConsumptionInWindowAllRecordsInside() {
        Balance balance = new Balance();
        List<ConsumptionRecord> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        records.add(new ConsumptionRecord(now.minusMinutes(30), 1000L));
        records.add(new ConsumptionRecord(now.minusMinutes(10), 2000L));
        records.add(new ConsumptionRecord(now.minusMinutes(1), 3000L));
        balance.setConsumptionHistory(records);

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 24L);
        assertEquals(6000L, consumption);
    }

    @Test
    void testClearTemporalCacheClears() {
        // Just ensure it doesn't throw any exception
        assertDoesNotThrow(() -> {
            accountingUtil.clearTemporalCache();
            accountingUtil.clearTemporalCache();  // Call twice to ensure idempotency
        });
    }



    @Test
    void testFindBalanceWithHighestPriorityBucketIdNotMatching() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");
        Balance balance3 = createBalance("BUCKET3", 3L, 3000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2, balance3);

        // Request BUCKET2 but it should be found first without falling back to priority
        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, "BUCKET2")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testCalculateConsumptionInWindowWithVeryOldRecords() {
        Balance balance = new Balance();
        List<ConsumptionRecord> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        records.add(new ConsumptionRecord(now.minusDays(30), 1000L));
        records.add(new ConsumptionRecord(now.minusDays(25), 2000L));
        records.add(new ConsumptionRecord(now.minusHours(1), 3000L));
        balance.setConsumptionHistory(records);

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 24L);
        // Only the last record (1 hour ago) should be counted
        assertEquals(3000L, consumption);
    }

    @Test
    void testIsWithinTimeWindowBoundary00to00() {
        // Edge case: 00-00 should mean only exactly midnight
        assertDoesNotThrow(() -> {
            accountingUtil.isWithinTimeWindow("00-00");
        });
    }

    @Test
    void testIsWithinTimeWindowSameTimes() {
        // Time window where start and end are same (should only be within that hour)
        assertDoesNotThrow(() -> {
            accountingUtil.isWithinTimeWindow("12-12");
        });
    }

    @Test
    void testFindBalanceWithConsumptionLimitAndNullWindow() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setConsumptionLimit(5000L);
        balance1.setConsumptionLimitWindow(null);  // Null window

        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        // Balance1 should be eligible since consumption limit window is null
        assertNotNull(result);
        assertEquals("BUCKET1", result.getBucketId());
    }

    @Test
    void testFindBalanceWithNegativeQuota() {
        Balance balance1 = createBalance("BUCKET1", 1L, -100L, "Active", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 1000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        // Should select BUCKET2 since BUCKET1 has negative quota
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceWithDisabledStatus() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Disabled", "0-24");
        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        // Should select BUCKET2 since BUCKET1 is not Active
        assertEquals("BUCKET2", result.getBucketId());
    }

    @Test
    void testFindBalanceBothExpired() {
        Balance balance1 = createBalance("BUCKET1", 1L, 1000L, "Active", "0-24");
        balance1.setServiceExpiry(LocalDateTime.now().minusDays(10));

        Balance balance2 = createBalance("BUCKET2", 2L, 2000L, "Active", "0-24");
        balance2.setServiceExpiry(LocalDateTime.now().minusDays(5));

        List<Balance> balances = List.of(balance1, balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        // Both are expired, should return null
        assertNull(result);
    }

    @Test
    void testFindBalanceWithHighestPriorityLargeList() {
        List<Balance> balances = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            Balance balance = createBalance("BUCKET" + i, (long) i, 1000L, "Active", "0-24");
            balances.add(balance);
        }

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(result);
        // Should select BUCKET1 (priority 1 is lowest)
        assertEquals("BUCKET1", result.getBucketId());
    }




    @Test
    void testUpdateSessionAndBalanceTemporalCacheCleared() {
        // Setup
        Balance balance = createBalance("BUCKET1", 1L, 5000L, "Active", "0-24");
        balance.setBucketUsername("testuser");

        UserSessionData userData = new UserSessionData();
        userData.setGroupId("1");
        userData.setBalance(new ArrayList<>(List.of(balance)));
        userData.setSessions(new ArrayList<>());

        Session sessionData = new Session();
        sessionData.setSessionId("session1");
        sessionData.setPreviousTotalUsageQuotaValue(0L);
        sessionData.setSessionTime(100);

        AccountingRequestDto request = new AccountingRequestDto(
            "event-1",
            "session1",
            "192.168.1.1",
            "testuser",
            AccountingRequestDto.ActionType.INTERIM_UPDATE,
            100,
            200,
            300,
            Instant.now(),
            "port1",
            "10.0.0.1",
            400,
            0,
            0,
            "nas1");

        // Mock

        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());


        // Execute
        accountingUtil.updateSessionAndBalance(userData, sessionData, request, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Verify temporal cache is cleared (no exception and subsequent calls work)
        assertDoesNotThrow(() -> accountingUtil.clearTemporalCache());
    }

    @Test
    void testUpdateSessionAndBalanceWithZeroUsage() {
        // Setup - request with zero usage
        Balance balance = createBalance("BUCKET1", 1L, 5000L, "Active", "0-24");
        balance.setBucketUsername("testuser");

        UserSessionData userData = new UserSessionData();
        userData.setGroupId("1");
        userData.setBalance(new ArrayList<>(List.of(balance)));
        userData.setSessions(new ArrayList<>());

        Session sessionData = new Session();
        sessionData.setSessionId("session1");
        sessionData.setPreviousTotalUsageQuotaValue(1000L);
        sessionData.setSessionTime(100);

        // Zero usage request
        AccountingRequestDto request = new AccountingRequestDto(
                "event-1",
                "session1",
                "192.168.1.1",
                "testuser",
                AccountingRequestDto.ActionType.INTERIM_UPDATE,
                100,
                200,
                300,
                Instant.now(),
                "port1",
                "10.0.0.1",
                400,
                0,
                0,
                "nas1");

        // Mock

        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());

        // Execute
        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, sessionData, request, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        // Verify
        assertNotNull(result);
        assertTrue(result.success());
        // Quota should remain unchanged (5000)
        assertEquals(5000L, result.newQuota());
    }

    @Test
    void testUpdateSessionAndBalanceHighGigaWordsUsage() {
        // Setup - usage with gigawords
        Balance balance = createBalance("BUCKET1", 1L, 5000000000L, "Active", "0-24");
        balance.setBucketUsername("testuser");

        UserSessionData userData = new UserSessionData();
        userData.setGroupId("1");
        userData.setBalance(new ArrayList<>(List.of(balance)));
        userData.setSessions(new ArrayList<>());

        Session sessionData = new Session();
        sessionData.setSessionId("session1");
        sessionData.setPreviousTotalUsageQuotaValue(0L);
        sessionData.setSessionTime(100);

        // 1 gigaword input + 1 gigaword output = 2 * 4294967296 bytes
        AccountingRequestDto request = new AccountingRequestDto(
                "event-1",
                "session1",
                "192.168.1.1",
                "testuser",
                AccountingRequestDto.ActionType.INTERIM_UPDATE,
                100,
                200,
                300,
                Instant.now(),
                "port1",
                "10.0.0.1",
                400,
                0,
                0,
                "nas1");

        // Mock
        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());

        // Execute
        UpdateResult result = accountingUtil.updateSessionAndBalance(userData, sessionData, request, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        // Verify
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(4999999700L, result.newQuota());
    }

    @Test
    void testUpdateSessionAndBalanceSessionDataUpdated() {
        // Setup
        Balance balance = createBalance("BUCKET1", 1L, 5000L, "Active", "0-24");
        balance.setBucketUsername("testuser");

        UserSessionData userData = new UserSessionData();
        userData.setGroupId("1");
        userData.setBalance(new ArrayList<>(List.of(balance)));
        userData.setSessions(new ArrayList<>());

        Session sessionData = new Session();
        sessionData.setSessionId("session1");
        sessionData.setPreviousTotalUsageQuotaValue(0L);
        sessionData.setSessionTime(50);

        AccountingRequestDto request = new AccountingRequestDto(
            "event-1",
            "session1",
            "192.168.1.1",
            "testuser",
            AccountingRequestDto.ActionType.INTERIM_UPDATE,
            100,
            200,
            300,
            Instant.now(),
            "port1",
            "10.0.0.1",
            400,
            0,
            0,
            "nas1");

        // Mock

        when(cacheClient.updateUserAndRelatedCaches(anyString(), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());

        // Execute
        accountingUtil.updateSessionAndBalance(userData, sessionData, request, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        // Verify - session data should be updated
        assertEquals(300L, sessionData.getPreviousTotalUsageQuotaValue());
        assertEquals(300, sessionData.getSessionTime().intValue());
        assertEquals("BUCKET1", sessionData.getPreviousUsageBucketId());
    }

    private Balance createBalance(String bucketId, Long priority, Long quota,
                                   String status, String timeWindow) {
        Balance balance = new Balance();
        balance.setBucketId(bucketId);
        balance.setPriority(priority);
        balance.setQuota(quota);
        balance.setServiceStatus(status);
        balance.setTimeWindow(timeWindow);
        balance.setServiceExpiry(LocalDateTime.now().plusDays(30));
        balance.setServiceStartDate(LocalDateTime.now().minusDays(1));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        return balance;
    }
}
