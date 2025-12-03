package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.AccountingRequestDto;
import com.csg.airtel.aaa4j.domain.model.UpdateResult;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.Session;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.domain.produce.AccountProducer;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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

    private AccountingUtil accountingUtil;

    @BeforeEach
    void setUp() {
        accountingUtil = new AccountingUtil(accountProducer, cacheClient, coaService);
    }

    @Test
    void testFindBalanceWithHighestPriorityWithUnlimitedBucket() {
        List<Balance> balances = new ArrayList<>();

        // Regular bucket with quota
        Balance regularBalance = createBalance("REGULAR-1", 5000L, 10000L, 1L);
        balances.add(regularBalance);

        // Unlimited bucket (initialBalance = 0, quota = 0)
        Balance unlimitedBalance = createBalance("UNLIMITED-1", 0L, 0L, 2L);
        balances.add(unlimitedBalance);

        accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(result -> {
                    assertNotNull(result);
                    // Should select the unlimited bucket since it has higher priority
                    assertEquals("UNLIMITED-1", result.getBucketId());
                    return true;
                });
    }

    @Test
    void testFindBalanceWithHighestPriorityWithRegularBucket() {
        List<Balance> balances = new ArrayList<>();

        // Regular bucket with quota
        Balance regularBalance = createBalance("REGULAR-1", 5000L, 10000L, 1L);
        balances.add(regularBalance);

        accountingUtil.findBalanceWithHighestPriority(balances, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(result -> {
                    assertNotNull(result);
                    assertEquals("REGULAR-1", result.getBucketId());
                    return true;
                });
    }

    @Test
    void testUpdateSessionAndBalanceWithUnlimitedBucket() {
        UserSessionData userData = createUserSessionDataWithUnlimitedBucket();
        AccountingRequestDto request = createAccountingRequest(100);

        when(cacheClient.cacheUserData(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        accountingUtil.updateSessionAndBalance(userData, null, request, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(result -> {
                    assertTrue(result.isSuccess());
                    // For unlimited bucket, quota should remain 0
                    assertEquals(0L, result.newQuota());
                    return true;
                });

        verify(cacheClient, times(1)).cacheUserData(any(), any());
    }

    @Test
    void testUpdateSessionAndBalanceWithRegularBucket() {
        UserSessionData userData = createUserSessionDataWithRegularBucket();
        AccountingRequestDto request = createAccountingRequest(100);

        when(cacheClient.cacheUserData(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        accountingUtil.updateSessionAndBalance(userData, null, request, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertItem(result -> {
                    assertTrue(result.isSuccess());
                    // For regular bucket, quota should be decremented
                    assertTrue(result.newQuota() < 10000L);
                    return true;
                });

        verify(cacheClient, times(1)).cacheUserData(any(), any());
    }

    @Test
    void testClearTemporalCache() {
        accountingUtil.clearTemporalCache();
        // Should not throw any exception
    }

    private Balance createBalance(String bucketId, Long quota, Long initialBalance, Long priority) {
        Balance balance = new Balance();
        balance.setBucketId(bucketId);
        balance.setServiceId("1");
        balance.setQuota(quota);
        balance.setInitialBalance(initialBalance);
        balance.setPriority(priority);
        balance.setServiceStatus("Active");
        balance.setTimeWindow("0-24");
        balance.setServiceExpiry(LocalDateTime.now().plusDays(30));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(30));
        balance.setServiceStartDate(LocalDateTime.now().minusDays(1));
        return balance;
    }

    private UserSessionData createUserSessionDataWithUnlimitedBucket() {
        UserSessionData userData = new UserSessionData();
        userData.setUserName("test-user");

        Balance unlimitedBalance = createBalance("UNLIMITED-1", 0L, 0L, 1L);
        userData.setBalance(List.of(unlimitedBalance));

        Session session = new Session(
                "session-id-123",
                LocalDateTime.now(),
                null,
                50,
                1000L,
                "192.168.1.1",
                "10.0.0.1"
        );
        userData.setSessions(new ArrayList<>(List.of(session)));

        return userData;
    }

    private UserSessionData createUserSessionDataWithRegularBucket() {
        UserSessionData userData = new UserSessionData();
        userData.setUserName("test-user");

        Balance regularBalance = createBalance("REGULAR-1", 10000L, 10000L, 1L);
        userData.setBalance(List.of(regularBalance));

        Session session = new Session(
                "session-id-123",
                LocalDateTime.now(),
                null,
                50,
                1000L,
                "192.168.1.1",
                "10.0.0.1"
        );
        userData.setSessions(new ArrayList<>(List.of(session)));

        return userData;
    }

    private AccountingRequestDto createAccountingRequest(int sessionTime) {
        return new AccountingRequestDto(
                "event-id-123",
                "session-id-123",
                "10.0.0.1",
                "test-user",
                AccountingRequestDto.ActionType.INTERIM_UPDATE,
                1000,
                2000,
                sessionTime,
                Instant.now(),
                "NAS-PORT-1",
                "192.168.1.1",
                0,
                1,
                2,
                "NAS-1"
        );
    }
}
