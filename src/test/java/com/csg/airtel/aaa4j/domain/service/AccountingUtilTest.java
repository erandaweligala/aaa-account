package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.ConsumptionRecord;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountingUtil Tests")
class AccountingUtilTest {

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private CacheClient cacheClient;

    @Mock
    private COAService coaService;

    private AccountingUtil accountingUtil;

    @BeforeEach
    void setUp() {
        accountingUtil = new AccountingUtil(accountProducer, cacheClient, coaService);
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
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, windowDays);

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
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, windowDays);

            // Then: Only second window records should be counted
            assertEquals(750000L, totalConsumption,
                "Should only sum consumption in second window period (days 30-59)");
        }

        @Test
        @DisplayName("Should calculate consumption in third window period")
        void shouldCalculateConsumptionInThirdWindowPeriod() {
            // Given: Service started on 2025-12-01, window is 30 days, today is 2026-02-15 (day 76)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            LocalDateTime serviceStartDateTime = serviceStartDate.atStartOfDay();
            long windowDays = 30L;

            Balance balance = createBalance(serviceStartDateTime, 1000000L, windowDays);

            // Add consumption records across all three windows
            List<ConsumptionRecord> history = new ArrayList<>();
            // First window: 2025-12-01 to 2025-12-30 (days 0-29)
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 100000L, 1));
            // Second window: 2025-12-31 to 2026-01-29 (days 30-59)
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 15), 200000L, 1));
            // Third window: 2026-01-30 to 2026-02-28 (days 60-89)
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 30), 300000L, 1)); // Day 60
            history.add(new ConsumptionRecord(LocalDate.of(2026, 2, 10), 400000L, 1)); // Day 71
            history.add(new ConsumptionRecord(LocalDate.of(2026, 2, 15), 500000L, 1)); // Day 76 (today)
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, windowDays);

            // Then: Only third window records should be counted
            assertEquals(1200000L, totalConsumption,
                "Should only sum consumption in third window period (days 60-89)");
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
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, windowDays);

            // Then: Only third window records should be counted
            assertEquals(1200000L, totalConsumption,
                "Should only sum consumption in third 7-day window");
        }

        @Test
        @DisplayName("Should return zero when no consumption in current window")
        void shouldReturnZeroWhenNoConsumptionInCurrentWindow() {
            // Given: Service started on 2025-12-01, window is 30 days, today is 2026-01-15 (day 45)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            LocalDateTime serviceStartDateTime = serviceStartDate.atStartOfDay();
            long windowDays = 30L;

            Balance balance = createBalance(serviceStartDateTime, 1000000L, windowDays);

            // Add consumption only in first window (days 0-29)
            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 500000L, 1));
            // Currently in second window (days 30-59), but no records there
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, windowDays);

            // Then
            assertEquals(0L, totalConsumption,
                "Should return zero when no consumption in current window");
        }

        @Test
        @DisplayName("Should handle consumption exactly on window boundaries")
        void shouldHandleConsumptionOnWindowBoundaries() {
            // Given: Service started on 2025-12-01, window is 30 days
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            LocalDateTime serviceStartDateTime = serviceStartDate.atStartOfDay();
            long windowDays = 30L;

            Balance balance = createBalance(serviceStartDateTime, 1000000L, windowDays);

            // Add consumption on exact window boundaries
            List<ConsumptionRecord> history = new ArrayList<>();
            // First window: 2025-12-01 to 2025-12-30 (days 0-29)
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 1), 100000L, 1));  // First day of window 1
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 30), 150000L, 1)); // Last day of window 1
            // Second window: 2025-12-31 to 2026-01-29 (days 30-59)
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 31), 200000L, 1)); // First day of window 2
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 29), 250000L, 1));  // Last day of window 2
            balance.setConsumptionHistory(history);

            // When: Calculate for second window period
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, windowDays);

            // Then: Should include both boundary records of second window
            assertEquals(450000L, totalConsumption,
                "Should include consumption on window boundaries");
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
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, windowDays);

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
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, windowDays);

            // Then: Should only include last 7 days
            assertEquals(1200000L, totalConsumption,
                "Should exclude records older than rolling window");
        }
    }

    @Nested
    @DisplayName("Empty and Edge Case Tests")
    class EmptyAndEdgeCaseTests {

        @Test
        @DisplayName("Should return zero when consumption history is null")
        void shouldReturnZeroWhenConsumptionHistoryIsNull() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000L, 30L);
            balance.setConsumptionHistory(null);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

            // Then
            assertEquals(0L, totalConsumption,
                "Should return zero when consumption history is null");
        }

        @Test
        @DisplayName("Should return zero when consumption history is empty")
        void shouldReturnZeroWhenConsumptionHistoryIsEmpty() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000L, 30L);
            balance.setConsumptionHistory(new ArrayList<>());

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

            // Then
            assertEquals(0L, totalConsumption,
                "Should return zero when consumption history is empty");
        }

        @Test
        @DisplayName("Should handle single consumption record")
        void shouldHandleSingleConsumptionRecord() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000L, 30L);

            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 500000L, 1));
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

            // Then
            assertEquals(500000L, totalConsumption,
                "Should correctly handle single consumption record");
        }

        @Test
        @DisplayName("Should handle very first day of service")
        void shouldHandleVeryFirstDayOfService() {
            // Given: Today is the first day of service
            LocalDate serviceStartDate = LocalDate.now();
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000L, 30L);

            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(serviceStartDate, 100000L, 1));
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

            // Then
            assertEquals(100000L, totalConsumption,
                "Should include consumption from first day of service");
        }

        @Test
        @DisplayName("Should handle large window sizes")
        void shouldHandleLargeWindowSizes() {
            // Given: 365-day window (annual limit)
            LocalDate serviceStartDate = LocalDate.of(2025, 1, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000000L, 365L);

            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(LocalDate.of(2025, 1, 15), 100000000L, 1));
            history.add(new ConsumptionRecord(LocalDate.of(2025, 6, 15), 200000000L, 1));
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 300000000L, 1));
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 365L);

            // Then
            assertEquals(600000000L, totalConsumption,
                "Should handle large window sizes");
        }
    }

    @Nested
    @DisplayName("Time Window Tests")
    class TimeWindowTests {

        @Test
        @DisplayName("Should validate time window for full day")
        void shouldValidateTimeWindowForFullDay() {
            // Given: 00-24 time window (all day)
            String timeWindow = "00-24";

            // When
            boolean isWithin = accountingUtil.isWithinTimeWindow(timeWindow);

            // Then
            assertTrue(isWithin, "Should be within time window for 00-24");
        }

        @Test
        @DisplayName("Should validate time window for business hours")
        void shouldValidateTimeWindowForBusinessHours() {
            // Given: 08-18 time window (business hours)
            String timeWindow = "08-18";

            // When
            boolean isWithin = accountingUtil.isWithinTimeWindow(timeWindow);

            // Then: Result depends on current time, but should not throw exception
            assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow(timeWindow),
                "Should handle business hours time window");
        }

        @Test
        @DisplayName("Should handle overnight time window")
        void shouldHandleOvernightTimeWindow() {
            // Given: 22-06 time window (overnight)
            String timeWindow = "22-06";

            // When/Then: Should not throw exception
            assertDoesNotThrow(() -> accountingUtil.isWithinTimeWindow(timeWindow),
                "Should handle overnight time window");
        }

        @Test
        @DisplayName("Should throw exception for null time window")
        void shouldThrowExceptionForNullTimeWindow() {
            // When/Then
            assertThrows(IllegalArgumentException.class,
                () -> accountingUtil.isWithinTimeWindow(null),
                "Should throw exception for null time window");
        }

        @Test
        @DisplayName("Should throw exception for empty time window")
        void shouldThrowExceptionForEmptyTimeWindow() {
            // When/Then
            assertThrows(IllegalArgumentException.class,
                () -> accountingUtil.isWithinTimeWindow(""),
                "Should throw exception for empty time window");
        }

        @Test
        @DisplayName("Should throw exception for invalid time window format")
        void shouldThrowExceptionForInvalidTimeWindowFormat() {
            // When/Then
            assertThrows(IllegalArgumentException.class,
                () -> accountingUtil.isWithinTimeWindow("08:00-18:00"),
                "Should throw exception for invalid time window format");
        }

        @Test
        @DisplayName("Should throw exception for time window without separator")
        void shouldThrowExceptionForTimeWindowWithoutSeparator() {
            // When/Then
            assertThrows(IllegalArgumentException.class,
                () -> accountingUtil.isWithinTimeWindow("0824"),
                "Should throw exception for time window without separator");
        }
    }

    @Nested
    @DisplayName("Multiple Consumption Records Tests")
    class MultipleConsumptionRecordsTests {

        @Test
        @DisplayName("Should aggregate multiple records from same day")
        void shouldAggregateMultipleRecordsFromSameDay() {
            // Given
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 1000000L, 30L);

            LocalDate sameDay = LocalDate.of(2025, 12, 15);
            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(sameDay, 100000L, 1));
            history.add(new ConsumptionRecord(sameDay, 200000L, 1));
            history.add(new ConsumptionRecord(sameDay, 300000L, 1));
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

            // Then
            assertEquals(600000L, totalConsumption,
                "Should sum all records from the same day");
        }

        @Test
        @DisplayName("Should handle consumption records spanning multiple windows")
        void shouldHandleConsumptionRecordsSpanningMultipleWindows() {
            // Given: Service started on 2025-12-01, 30-day windows, today is 2026-02-20 (day 81)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 3000000L, 30L);

            List<ConsumptionRecord> history = new ArrayList<>();
            // Window 1: days 0-29 (2025-12-01 to 2025-12-30)
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 5), 100000L, 1));
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 150000L, 1));
            // Window 2: days 30-59 (2025-12-31 to 2026-01-29)
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 5), 200000L, 1));
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 20), 250000L, 1));
            // Window 3: days 60-89 (2026-01-30 to 2026-02-28)
            history.add(new ConsumptionRecord(LocalDate.of(2026, 2, 5), 300000L, 1));
            history.add(new ConsumptionRecord(LocalDate.of(2026, 2, 15), 350000L, 1));
            history.add(new ConsumptionRecord(LocalDate.of(2026, 2, 20), 400000L, 1)); // Today
            balance.setConsumptionHistory(history);

            // When: Calculate for current (third) window
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

            // Then: Only window 3 records should be counted
            assertEquals(1050000L, totalConsumption,
                "Should only include consumption from current window period");
        }
    }

    @Nested
    @DisplayName("Real-world Scenario Tests")
    class RealWorldScenarioTests {

        @Test
        @DisplayName("Should handle monthly data plan with 30-day windows")
        void shouldHandleMonthlyDataPlanWith30DayWindows() {
            // Given: 600GB total over 90 days, 200GB per 30-day window
            // Service started 2025-12-01, today is 2025-12-25 (day 24)
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 600_000_000_000L, 30L);
            balance.setConsumptionLimit(200_000_000_000L); // 200GB limit per window

            // Simulate realistic usage pattern in first window
            List<ConsumptionRecord> history = new ArrayList<>();
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 1), 5_000_000_000L, 120));   // 5GB
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 5), 10_000_000_000L, 250));  // 10GB
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 10), 15_000_000_000L, 300)); // 15GB
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 20_000_000_000L, 400)); // 20GB
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 20), 25_000_000_000L, 500)); // 25GB
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 25), 30_000_000_000L, 600)); // 30GB
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

            // Then: Should have consumed 105GB in first window
            assertEquals(105_000_000_000L, totalConsumption,
                "Should correctly calculate consumption in first 30-day window");
            assertTrue(totalConsumption < balance.getConsumptionLimit(),
                "Should be under consumption limit");
        }

        @Test
        @DisplayName("Should reset consumption in new window period")
        void shouldResetConsumptionInNewWindowPeriod() {
            // Given: Service started 2025-12-01, today is 2026-01-05 (day 35, in second window)
            // User consumed 190GB in first window, now consuming in second window
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 600_000_000_000L, 30L);
            balance.setConsumptionLimit(200_000_000_000L); // 200GB per window

            List<ConsumptionRecord> history = new ArrayList<>();
            // First window consumption: 190GB (almost at limit)
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 15), 90_000_000_000L, 1800));
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 25), 100_000_000_000L, 2000));
            // Second window consumption: 10GB (resets, not 200GB)
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 2), 5_000_000_000L, 100));
            history.add(new ConsumptionRecord(LocalDate.of(2026, 1, 5), 5_000_000_000L, 100));
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

            // Then: Should only count second window consumption (10GB, not 200GB)
            assertEquals(10_000_000_000L, totalConsumption,
                "Should reset consumption in new window period");
            assertTrue(totalConsumption < balance.getConsumptionLimit(),
                "Should be well under limit in new window");
        }

        @Test
        @DisplayName("Should handle weekly fair usage policy")
        void shouldHandleWeeklyFairUsagePolicy() {
            // Given: 7-day weekly fair usage with 50GB per week
            LocalDate serviceStartDate = LocalDate.of(2025, 12, 1);
            Balance balance = createBalance(serviceStartDate.atStartOfDay(), 200_000_000_000L, 7L);
            balance.setConsumptionLimit(50_000_000_000L); // 50GB per week

            // Today is 2025-12-25 (day 24, in 4th week)
            List<ConsumptionRecord> history = new ArrayList<>();
            // Week 1 (days 0-6): 45GB
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 3), 45_000_000_000L, 900));
            // Week 2 (days 7-13): 48GB
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 10), 48_000_000_000L, 960));
            // Week 3 (days 14-20): 49GB
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 17), 49_000_000_000L, 980));
            // Week 4 (days 21-27): 35GB so far (current week)
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 22), 20_000_000_000L, 400));
            history.add(new ConsumptionRecord(LocalDate.of(2025, 12, 25), 15_000_000_000L, 300));
            balance.setConsumptionHistory(history);

            // When
            long totalConsumption = accountingUtil.calculateConsumptionInWindow(balance, 7L);

            // Then: Should only count current week (week 4)
            assertEquals(35_000_000_000L, totalConsumption,
                "Should only count consumption in current week");
            assertTrue(totalConsumption < balance.getConsumptionLimit(),
                "Should be under weekly limit");
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
