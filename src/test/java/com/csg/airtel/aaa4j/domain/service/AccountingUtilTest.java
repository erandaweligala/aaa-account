package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.ConsumptionRecord;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
