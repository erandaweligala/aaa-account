package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountingUtilTest {

    @Mock
    private AccountProducer accountProducer;

    @Mock
    private CacheClient cacheClient;

    @Mock
    private COAService coaService;

    @Mock
    private QuotaNotificationService quotaNotificationService;

    private AccountingUtil accountingUtil;

    @BeforeEach
    void setUp() {
        accountingUtil = new AccountingUtil(
            accountProducer, cacheClient, coaService, quotaNotificationService
        );
    }

    @Test
    void testFindBalanceWithHighestPriority_SingleBalance() {
        List<Balance> balances = new ArrayList<>();
        Balance balance = createBalance(1L, 1000L);
        balances.add(balance);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals(balance.getBucketId(), result.getBucketId());
    }

    @Test
    void testFindBalanceWithHighestPriority_EmptyList() {
        List<Balance> balances = new ArrayList<>();

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNull(result);
    }

    @Test
    void testFindBalanceWithHighestPriority_MultiplePriorities() {
        List<Balance> balances = new ArrayList<>();
        Balance balance1 = createBalance(1L, 1000L);
        balance1.setPriority(2L);
        Balance balance2 = createBalance(2L, 2000L);
        balance2.setPriority(1L);
        balances.add(balance1);
        balances.add(balance2);

        Balance result = accountingUtil.findBalanceWithHighestPriority(balances, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertNotNull(result);
        assertEquals(1L, result.getPriority());
    }

    @Test
    void testIsWithinTimeWindow_ValidWindow() {
        boolean result = accountingUtil.isWithinTimeWindow("0-24");
        assertTrue(result);
    }

    @Test
    void testIsWithinTimeWindow_InvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("invalid");
        });
    }

    @Test
    void testIsWithinTimeWindow_NullInput() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow(null);
        });
    }

    @Test
    void testIsWithinTimeWindow_EmptyString() {
        assertThrows(IllegalArgumentException.class, () -> {
            accountingUtil.isWithinTimeWindow("");
        });
    }

    @Test
    void testCalculateConsumptionInWindow() {
        Balance balance = createBalance(1L, 1000L);
        balance.setServiceStartDate(LocalDateTime.now().minusDays(15));
        balance.setConsumptionHistory(new ArrayList<>());

        long consumption = accountingUtil.calculateConsumptionInWindow(balance, 30L);

        assertEquals(0L, consumption);
    }

    @Test
    void testClearTemporalCache() {
        accountingUtil.clearTemporalCache();
        // Just verify it doesn't throw exception
    }

    private Balance createBalance(Long priority, Long quota) {
        Balance balance = new Balance();
        balance.setBucketId("bucket-" + priority);
        balance.setServiceId("service-1");
        balance.setPriority(priority);
        balance.setQuota(quota);
        balance.setInitialBalance(2000L);
        balance.setServiceExpiry(LocalDateTime.now().plusDays(30));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        balance.setServiceStartDate(LocalDateTime.now().minusDays(1));
        balance.setServiceStatus("Active");
        balance.setTimeWindow("0-24");
        return balance;
    }
}
