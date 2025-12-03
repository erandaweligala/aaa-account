package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.ConsumptionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConsumptionLimitCalculator Tests")
class ConsumptionLimitCalculatorTest {

    private ConsumptionLimitCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ConsumptionLimitCalculator();
    }

    @Nested
    @DisplayName("Fixed Window Consumption Tests")
    class FixedWindowConsumptionTests {

        @Test
        @DisplayName("Should calculate consumption in first window period")
        void shouldCalculateConsumptionInFirstWindowPeriod() {
            // Given: Service started on 2025-12-01, window is 30 days, today is 2025-12-15 (day 14)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            LocalDateTime serviceStartDateTime = serviceStartDate.atStartOfDay();
            long windowDays = 30L;

            Balance balance = createBalance(serviceStartDateTime, 1000000L, windowDays);

            // Add consumption records in first window (days 0-29)
            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 1), 100000L, 1));  // Day 0
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 5), 150000L, 1));  // Day 4
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 10), 200000L, 1)); // Day 9
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 250000L, 1)); // Day 14
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = calculator.calculateConsumptionInWindow(balance, windowDays);

            // Then: All records should be included (they're all in first window: days 0-29)
            assertEquals(700000L, totalConsumption,
                "Should sum all consumption in first window period");
        }

        @Test
        @DisplayName("Should calculate consumption in second window period")
        void shouldCalculateConsumptionInSecondWindowPeriod() {
            // Given: Service started on 2025-12-01, window is 30 days, today is 2026-01-10 (day 40)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            LocalDateTime serviceStartDateTime = serviceStartDate.atStartOfDay();
            long windowDays = 30L;

            Balance balance = createBalance(serviceStartDateTime, 1000000L, windowDays);

            // Add consumption records across windows
            List<ConsumptionRecord> history = new ArrayList<>();
            // First window (days 0-29): 2025-12-01 to 2025-12-30
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 1), 100000L, 1));
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 150000L, 1));
            // Second window (days 30-59): 2025-12-31 to 2026-01-29
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 31), 200000L, 1)); // Day 30
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 5), 250000L, 1));   // Day 35
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 10), 300000L, 1));  // Day 40 (today)
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = calculator.calculateConsumptionInWindow(balance, windowDays);

            // Then: Only second window records should be counted
            assertEquals(750000L, totalConsumption,
                "Should only sum consumption in second window period (days 30-59)");
        }

        @Test
        @DisplayName("Should handle 7-day consumption window")
        void shouldHandle7DayConsumptionWindow() {
            // Given: Service started on 2025-12-01, window is 7 days, today is 2025-12-20 (day 19)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            LocalDateTime serviceStartDateTime = serviceStartDate.atStartOfDay();
            long windowDays = 7L;

            Balance balance = createBalance(serviceStartDateTime, 1000000L, windowDays);

            // Day 19 falls in third 7-day window (days 14-20)
            List<ConsumptionRecord> history = new ArrayList<>();
            // First window: days 0-6
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 1), 100000L, 1));
            // Second window: days 7-13
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 10), 200000L, 1));
            // Third window: days 14-20 (current)
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 300000L, 1)); // Day 14
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 18), 400000L, 1)); // Day 17
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 20), 500000L, 1)); // Day 19 (today)
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = calculator.calculateConsumptionInWindow(balance, windowDays);

            // Then: Only third window records should be counted
            assertEquals(1200000L, totalConsumption,
                "Should only sum consumption in third 7-day window");
        }
    }

    @Nested
    @DisplayName("Window Period Calculation Tests")
    class WindowPeriodCalculationTests {

        @Test
        @DisplayName("Should calculate first window period")
        void shouldCalculateFirstWindowPeriod() {
            // Given: Service started on 2025-12-01, today is 2025-12-15 (day 14)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            long windowDays = 30L;

            // When
            int windowPeriod = calculator.getCurrentWindowPeriod(serviceStartDate, windowDays);

            // Then
            assertEquals(1, windowPeriod, "Should be in first window period");
        }

        @Test
        @DisplayName("Should calculate second window period")
        void shouldCalculateSecondWindowPeriod() {
            // Given: Service started on 2025-12-01, today is 2026-01-10 (day 40)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            long windowDays = 30L;

            // When
            int windowPeriod = calculator.getCurrentWindowPeriod(serviceStartDate, windowDays);

            // Then
            assertEquals(2, windowPeriod, "Should be in second window period");
        }

        @Test
        @DisplayName("Should handle window period on exact boundary")
        void shouldHandleWindowPeriodOnExactBoundary() {
            // Given: Service started on 2025-12-01, today is 2025-12-31 (day 30, start of window 2)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            long windowDays = 30L;

            // When
            int windowPeriod = calculator.getCurrentWindowPeriod(serviceStartDate, windowDays);

            // Then
            assertEquals(2, windowPeriod, "Should be in second window period on boundary");
        }
    }

    @Nested
    @DisplayName("Fixed Window Bounds Tests")
    class FixedWindowBoundsTests {

        @Test
        @DisplayName("Should calculate first window bounds correctly")
        void shouldCalculateFirstWindowBoundsCorrectly() {
            // Given: Service started on 2025-12-01, window is 30 days
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            long windowDays = 30L;

            // When
            LocalDate[] bounds = calculator.calculateFixedWindowBounds(serviceStartDate, windowDays);

            // Then
            assertEquals(LocalDate.of(2025, 12, 1), bounds[0], "Start date should match service start");
            assertEquals(LocalDate.of(2025, 12, 30), bounds[1], "End date should be 29 days after start");
        }

        @Test
        @DisplayName("Should calculate second window bounds correctly")
        void shouldCalculateSecondWindowBoundsCorrectly() {
            // Given: Service started on 2025-12-01, window is 30 days, today is 2026-01-10 (day 40)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            long windowDays = 30L;

            // When
            LocalDate[] bounds = calculator.calculateFixedWindowBounds(serviceStartDate, windowDays);

            // Then
            assertEquals(LocalDate.of(2025, 12, 31), bounds[0], "Second window should start on day 30");
            assertEquals(LocalDate.of(2026, 1, 29), bounds[1], "Second window should end on day 59");
        }

        @Test
        @DisplayName("Should handle 7-day window bounds")
        void shouldHandle7DayWindowBounds() {
            // Given: Service started on 2025-12-01, window is 7 days
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            long windowDays = 7L;

            // When
            LocalDate[] bounds = calculator.calculateFixedWindowBounds(serviceStartDate, windowDays);

            // Then
            assertEquals(LocalDate.of(2025, 12, 1), bounds[0], "First window should start on service start");
            assertEquals(LocalDate.of(2025, 12, 7), bounds[1], "First window should end 6 days later");
        }
    }

    @Nested
    @DisplayName("Rolling Window Fallback Tests")
    class RollingWindowFallbackTests {

        @Test
        @DisplayName("Should use rolling window when serviceStartDate is null")
        void shouldUseRollingWindowWhenServiceStartDateIsNull() {
            // Given: No service start date (legacy behavior)
            long windowDays = 30L;
            Balance balance = createBalance(null, 1000000L, windowDays);

            LocalDate today = LocalDate.now();
            List<ConsumptionRecord> history = new ArrayList<>();
            // Add records in rolling 30-day window
            history.add(new ConsumptionRecord(today.minusDays(35), 100000L, 1)); // Outside window
            history.add(new ConsumptionRecord(today.minusDays(25), 200000L, 1)); // Inside window
            history.add(new ConsumptionRecord(today.minusDays(15), 300000L, 1)); // Inside window
            history.add(new ConsumptionRecord(today.minusDays(5), 400000L, 1));  // Inside window
            history.add(new ConsumptionRecord(today, 500000L, 1));               // Inside window
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = calculator.calculateConsumptionInWindow(balance, windowDays);

            // Then: Should use rolling window (last 30 days from today)
            assertEquals(1400000L, totalConsumption,
                "Should sum consumption in rolling 30-day window when serviceStartDate is null");
        }

        @Test
        @DisplayName("Should exclude records older than rolling window")
        void shouldExcludeRecordsOlderThanRollingWindow() {
            // Given: No service start date
            long windowDays = 7L;
            Balance balance = createBalance(null, 1000000L, windowDays);

            LocalDate today = LocalDate.now();
            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(today.minusDays(10), 100000L, 1)); // Outside window
            history.add(new ConsumptionRecord(today.minusDays(8), 200000L, 1));  // Outside window
            history.add(new ConsumptionRecord(today.minusDays(6), 300000L, 1));  // Inside window
            history.add(new ConsumptionRecord(today.minusDays(3), 400000L, 1));  // Inside window
            history.add(new ConsumptionRecord(today, 500000L, 1));               // Inside window

            // When
            long total = calculator.calculateRollingWindowConsumption(history, windowDays);

            // Then: Should only include last 7 days
            assertEquals(1200000L, total,
                "Should exclude records older than rolling window");
        }
    }

    @Nested
    @DisplayName("Consumption Limit Exceeded Tests")
    class ConsumptionLimitExceededTests {

        @Test
        @DisplayName("Should detect when consumption limit is exceeded")
        void shouldDetectWhenConsumptionLimitIsExceeded() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 200_000_000L, 30L);

            // When
            boolean exceeded = calculator.isConsumptionLimitExceeded(balance, 190_000_000L, 20_000_000L);

            // Then
            assertTrue(exceeded, "Should detect consumption limit exceeded");
        }

        @Test
        @DisplayName("Should not detect exceeded when under limit")
        void shouldNotDetectExceededWhenUnderLimit() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 200_000_000L, 30L);

            // When
            boolean exceeded = calculator.isConsumptionLimitExceeded(balance, 150_000_000L, 30_000_000L);

            // Then
            assertFalse(exceeded, "Should not detect exceeded when under limit");
        }

        @Test
        @DisplayName("Should handle when consumption limit is not configured")
        void shouldHandleWhenConsumptionLimitIsNotConfigured() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), null, null);

            // When
            boolean exceeded = calculator.isConsumptionLimitExceeded(balance, 999_999_999L, 999_999_999L);

            // Then
            assertFalse(exceeded, "Should not detect exceeded when limit not configured");
        }
    }

    @Nested
    @DisplayName("Cleanup Tests")
    class CleanupTests {

        @Test
        @DisplayName("Should cleanup old consumption records")
        void shouldCleanupOldConsumptionRecords() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000L, 30L);

            LocalDate windowStartDate = LocalDate.of(2025, 12, 15);
            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 1), 100000L, 1));  // Before window
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 10), 200000L, 1)); // Before window
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 300000L, 1)); // In window
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 20), 400000L, 1)); // In window
            balance.setConsumptionHistory(history);

            // When
            calculator.cleanupOldConsumptionRecords(balance, windowStartDate);

            // Then
            assertEquals(2, balance.getConsumptionHistory().size(),
                "Should keep only records from window start date onwards");
            assertEquals(LocalDate.of(2025, 12, 15), balance.getConsumptionHistory().get(0).getDate());
            assertEquals(LocalDate.of(2025, 12, 20), balance.getConsumptionHistory().get(1).getDate());
        }

        @Test
        @DisplayName("Should handle cleanup when history is empty")
        void shouldHandleCleanupWhenHistoryIsEmpty() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000L, 30L);
            balance.setConsumptionHistory(new ArrayList<>());

            // When/Then: Should not throw exception
            assertDoesNotThrow(() ->
                calculator.cleanupOldConsumptionRecords(balance, LocalDate.of(2025, 12, 15)));
        }

        @Test
        @DisplayName("Should handle cleanup when history is null")
        void shouldHandleCleanupWhenHistoryIsNull() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000L, 30L);
            balance.setConsumptionHistory(null);

            // When/Then: Should not throw exception
            assertDoesNotThrow(() ->
                calculator.cleanupOldConsumptionRecords(balance, LocalDate.of(2025, 12, 15)));
        }
    }

    @Nested
    @DisplayName("Has Consumption Limit Tests")
    class HasConsumptionLimitTests {

        @Test
        @DisplayName("Should return true when consumption limit is configured")
        void shouldReturnTrueWhenConsumptionLimitIsConfigured() {
            // Given
            Balance balance = createBalance(LocalDate.now().atStartOfDay(), 200_000_000L, 30L);

            // When
            boolean hasLimit = calculator.hasConsumptionLimit(balance);

            // Then
            assertTrue(hasLimit, "Should have consumption limit");
        }

        @Test
        @DisplayName("Should return false when consumption limit is null")
        void shouldReturnFalseWhenConsumptionLimitIsNull() {
            // Given
            Balance balance = createBalance(LocalDate.now().atStartOfDay(), null, 30L);

            // When
            boolean hasLimit = calculator.hasConsumptionLimit(balance);

            // Then
            assertFalse(hasLimit, "Should not have consumption limit when limit is null");
        }

        @Test
        @DisplayName("Should return false when consumption window is null")
        void shouldReturnFalseWhenConsumptionWindowIsNull() {
            // Given
            Balance balance = createBalance(LocalDate.now().atStartOfDay(), 200_000_000L, null);

            // When
            boolean hasLimit = calculator.hasConsumptionLimit(balance);

            // Then
            assertFalse(hasLimit, "Should not have consumption limit when window is null");
        }

        @Test
        @DisplayName("Should return false when consumption limit is zero")
        void shouldReturnFalseWhenConsumptionLimitIsZero() {
            // Given
            Balance balance = createBalance(LocalDate.now().atStartOfDay(), 0L, 30L);

            // When
            boolean hasLimit = calculator.hasConsumptionLimit(balance);

            // Then
            assertFalse(hasLimit, "Should not have consumption limit when limit is zero");
        }
    }

    // Helper method to create a Balance with common properties
    private Balance createBalance(LocalDateTime serviceStartDate, Long consumptionLimit, Long windowDays) {
        Balance balance = new Balance();
        balance.setBucketId("TEST_BUCKET_" + System.currentTimeMillis());
        balance.setServiceStartDate(serviceStartDate);
        balance.setServiceStatus("Active");
        balance.setConsumptionLimit(consumptionLimit);
        balance.setConsumptionLimitWindow(windowDays);
        balance.setQuota(1000000000L); // 1GB default quota
        balance.setPriority(1L);
        balance.setTimeWindow("00-24");
        balance.setServiceExpiry(LocalDateTime.now().plusDays(365));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(365));
        balance.setConsumptionHistory(new ArrayList<>());
        return balance;
    }
}
