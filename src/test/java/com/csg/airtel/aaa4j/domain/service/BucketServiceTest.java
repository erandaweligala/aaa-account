package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.response.ApiResponse;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BucketServiceTest {

    @Mock
    private CacheClient cacheClient;

    @InjectMocks
    private BucketService bucketService;

    private String userName;
    private Balance balance;
    private UserSessionData userData;

    @BeforeEach
    void setUp() {
        userName = "testuser";
        balance = new Balance();
        balance.setServiceId("SERVICE123");
        balance.setQuota(1000L);

        userData = new UserSessionData();
        userData.setUserName(userName);
        userData.setBalance(new ArrayList<>());
    }

    @Test
    void testAddBucketBalanceSuccess() {
        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.addBucketBalance(userName, balance)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Balance added successfully", response.getMessage());
        assertEquals(balance, response.getData());
        assertNotNull(response.getTimestamp());

        verify(cacheClient).getUserData(userName);
        verify(cacheClient).updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class));
    }

    @Test
    void testAddBucketBalanceWithNullUsername() {
        ApiResponse<Balance> response = bucketService.addBucketBalance(null, balance)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Username is required", response.getMessage());
        assertNull(response.getData());

        verify(cacheClient, never()).getUserData(any());
    }

    @Test
    void testAddBucketBalanceWithBlankUsername() {
        ApiResponse<Balance> response = bucketService.addBucketBalance("", balance)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Username is required", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testAddBucketBalanceWithNullBalance() {
        ApiResponse<Balance> response = bucketService.addBucketBalance(userName, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Balance is required", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testAddBucketBalanceWithExistingBalances() {
        Balance existingBalance = new Balance();
        existingBalance.setServiceId("EXISTING");
        existingBalance.setQuota(500L);
        userData.setBalance(List.of(existingBalance));

        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.addBucketBalance(userName, balance)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Balance added successfully", response.getMessage());
        verify(cacheClient).updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class));
    }

    @Test
    void testUpdateBucketBalanceSuccess() {
        String serviceId = "SERVICE123";
        Balance existingBalance = new Balance();
        existingBalance.setServiceId(serviceId);
        existingBalance.setQuota(500L);

        userData.setBalance(new ArrayList<>(List.of(existingBalance)));

        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, serviceId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Balance added successfully", response.getMessage());
        assertEquals(balance, response.getData());

        verify(cacheClient).getUserData(userName);
        verify(cacheClient).updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class));
    }

    @Test
    void testUpdateBucketBalanceWithNullUsername() {
        ApiResponse<Balance> response = bucketService.updateBucketBalance(null, balance, "SERVICE123")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Username is required", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testUpdateBucketBalanceWithBlankServiceId() {
        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, "")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Service Id is required", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testUpdateBucketBalanceWithMismatchedServiceId() {
        balance.setServiceId("SERVICE123");

        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, "DIFFERENT")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Balance serviceId must match the provided serviceId", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testUpdateBucketBalanceUserNotFound() {
        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().nullItem());

        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, "SERVICE123")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("User not found", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void testUpdateBucketBalanceWithNullBalanceList() {
        userData.setBalance(null);

        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, "SERVICE123")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertEquals("Balance added successfully", response.getMessage());
    }

    @Test
    void testUpdateBucketBalanceFailure() {
        userData.setBalance(new ArrayList<>());

        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache update failed")));

        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, "SERVICE123")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertTrue(response.getMessage().contains("Failed to update balance"));
        assertNull(response.getData());
    }

    @Test
    void testAddBucketBalanceFailure() {
        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class)))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Update failed")));

        ApiResponse<Balance> response = bucketService.addBucketBalance(userName, balance)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertNotNull(response);
        assertTrue(response.getMessage().contains("Failed to add balance"));
        assertNull(response.getData());
    }
}
