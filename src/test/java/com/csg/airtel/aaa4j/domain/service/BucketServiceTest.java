package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.response.ApiResponse;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.BalanceWrapper;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
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

    @Mock
    private COAService coaService;

    @InjectMocks
    private BucketService bucketService;

    private String testUserName;
    private BalanceWrapper balanceWrapper;
    private Balance testBalance;

    @BeforeEach
    void setUp() {
        testUserName = "testuser";
        testBalance = createSampleBalance();
        balanceWrapper = new BalanceWrapper(testBalance, 2L);
    }

    @Test
    void testAddBucketBalance_NullUserName() {
        ApiResponse<Balance> response = bucketService.addBucketBalance(null, balanceWrapper)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals(BucketService.USERNAME_IS_REQUIRED, response.getMessage());
    }

    @Test
    void testAddBucketBalance_BlankUserName() {
        ApiResponse<Balance> response = bucketService.addBucketBalance("  ", balanceWrapper)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals(BucketService.USERNAME_IS_REQUIRED, response.getMessage());
    }

    @Test
    void testAddBucketBalance_NullBalance() {
        ApiResponse<Balance> response = bucketService.addBucketBalance(testUserName, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals("Balance is required", response.getMessage());
    }

    @Test
    void testAddBucketBalance_NewUser() {
        when(cacheClient.getUserData(testUserName)).thenReturn(Uni.createFrom().nullItem());
        when(cacheClient.updateUserAndRelatedCaches(eq(testUserName), any(UserSessionData.class), eq(testUserName)))
            .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.addBucketBalance(testUserName, balanceWrapper)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("Bucket Added Successfully", response.getMessage());
        assertNotNull(response.getData());
        verify(cacheClient).updateUserAndRelatedCaches(eq(testUserName), any(UserSessionData.class), eq(testUserName));
    }

    @Test
    void testAddBucketBalance_ExistingUser() {
        UserSessionData existingUserData = createSampleUserData();
        when(cacheClient.getUserData(testUserName)).thenReturn(Uni.createFrom().item(existingUserData));
        when(cacheClient.updateUserAndRelatedCaches(eq(testUserName), any(UserSessionData.class), eq(testUserName)))
            .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.addBucketBalance(testUserName, balanceWrapper)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("Bucket Added Successfully", response.getMessage());
        verify(cacheClient).updateUserAndRelatedCaches(eq(testUserName), any(UserSessionData.class), eq(testUserName));
    }

    @Test
    void testUpdateBucketBalance_NullUserName() {
        Balance balance = createSampleBalance();
        String serviceId = "100";

        ApiResponse<Balance> response = bucketService.updateBucketBalance(null, balance, serviceId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals(BucketService.USERNAME_IS_REQUIRED, response.getMessage());
    }

    @Test
    void testUpdateBucketBalance_NullBalance() {
        ApiResponse<Balance> response = bucketService.updateBucketBalance(testUserName, null, "100")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals("Balance is required", response.getMessage());
    }

    @Test
    void testUpdateBucketBalance_NullServiceId() {
        Balance balance = createSampleBalance();

        ApiResponse<Balance> response = bucketService.updateBucketBalance(testUserName, balance, null)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals("Service Id is required", response.getMessage());
    }

    @Test
    void testUpdateBucketBalance_MismatchedServiceId() {
        Balance balance = createSampleBalance();
        balance.setServiceId("200");
        String serviceId = "100";

        ApiResponse<Balance> response = bucketService.updateBucketBalance(testUserName, balance, serviceId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertTrue(response.getMessage().contains("must match"));
    }

    @Test
    void testUpdateBucketBalance_UserNotFound() {
        Balance balance = createSampleBalance();
        balance.setServiceId("100");
        when(cacheClient.getUserData(testUserName)).thenReturn(Uni.createFrom().nullItem());

        ApiResponse<Balance> response = bucketService.updateBucketBalance(testUserName, balance, "100")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals(BucketService.USER_NOT_FOUND, response.getMessage());
    }

    @Test
    void testUpdateBucketBalance_Success() {
        Balance balance = createSampleBalance();
        balance.setServiceId("100");
        UserSessionData userData = createSampleUserData();
        when(cacheClient.getUserData(testUserName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(testUserName), any(UserSessionData.class), eq(testUserName)))
            .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.updateBucketBalance(testUserName, balance, "100")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("Updated balance Successfully", response.getMessage());
        verify(cacheClient).updateUserAndRelatedCaches(eq(testUserName), any(UserSessionData.class), eq(testUserName));
    }

    @Test
    void testTerminateSessions_UserNotFound() {
        when(cacheClient.getUserData(testUserName)).thenReturn(Uni.createFrom().nullItem());

        ApiResponse<Balance> response = bucketService.terminateSessions(testUserName, "session-123")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals(BucketService.USER_NOT_FOUND, response.getMessage());
    }

    @Test
    void testUpdateUserStatus_NullUserName() {
        ApiResponse<String> response = bucketService.updateUserStatus(null, "ACTIVE")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals(BucketService.USERNAME_IS_REQUIRED, response.getMessage());
    }

    @Test
    void testUpdateUserStatus_InvalidStatus() {
        ApiResponse<String> response = bucketService.updateUserStatus(testUserName, "INVALID")
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertTrue(response.getMessage().contains("Invalid status"));
    }

    private Balance createSampleBalance() {
        Balance balance = new Balance();
        balance.setBucketId("100");
        balance.setServiceId("100");
        balance.setQuota(1000L);
        balance.setInitialBalance(2000L);
        balance.setServiceExpiry(LocalDateTime.now().plusDays(30));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        return balance;
    }

    private UserSessionData createSampleUserData() {
        return UserSessionData.builder()
            .userName(testUserName)
            .balance(new ArrayList<>())
            .sessions(new ArrayList<>())
            .concurrency(2L)
            .build();
    }
}
