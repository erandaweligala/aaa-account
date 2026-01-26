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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
// todo pls fixed errors
@ExtendWith(MockitoExtension.class)
class BucketServiceAdvancedTest {

    @Mock
    private CacheClient cacheClient;

    @Mock
    private COAService coaService;

    private BucketService bucketService;

    @BeforeEach
    void setUp() {
        bucketService = new BucketService(cacheClient, coaService);
    }

    @Test
    void testAddBucketBalance_WithConcurrency() {
        String userName = "testuser";
        Balance balance = createBalance();
        BalanceWrapper wrapper = new BalanceWrapper();
        wrapper.setBalance(balance);
        wrapper.setConcurrency(5L);

        UserSessionData userData = createUserData(userName);
        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class), eq(userName)))
            .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.addBucketBalance(userName, wrapper)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals(5L, userData.getConcurrency());
        verify(cacheClient).updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class), eq(userName));
    }

    @Test
    void testAddBucketBalance_CacheUpdateFailure() {
        String userName = "testuser";
        Balance balance = createBalance();
        BalanceWrapper wrapper = new BalanceWrapper();
        wrapper.setBalance(balance);
        wrapper.setConcurrency(2L);

        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().nullItem());
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class), eq(userName)))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));

        ApiResponse<Balance> response = bucketService.addBucketBalance(userName, wrapper)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, response.getStatus());
    }

    @Test
    void testUpdateBucketBalance_BalanceNotFound() {
        String userName = "testuser";
        Balance balance = createBalance();
        balance.setServiceId("100");
        String serviceId = "100";

        UserSessionData userData = createUserData(userName);
        userData.getBalance().clear(); // No balances

        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));

        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, serviceId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertTrue(response.getMessage().contains("not found"));
    }

    @Test
    void testUpdateBucketBalance_Success() {
        String userName = "testuser";
        Balance balance = createBalance();
        balance.setServiceId("100");
        balance.setBucketId("1");
        String serviceId = "100";

        UserSessionData userData = createUserData(userName);
        Balance existingBalance = createBalance();
        existingBalance.setServiceId("100");
        existingBalance.setBucketId("1");
        userData.getBalance().add(existingBalance);

        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class), eq(userName)))
            .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, serviceId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.OK, response.getStatus());
        verify(cacheClient).updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class), eq(userName));
    }

    @Test
    void testUpdateBucketBalance_CacheError() {
        String userName = "testuser";
        Balance balance = createBalance();
        balance.setServiceId("100");
        String serviceId = "100";

        when(cacheClient.getUserData(userName))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));

        ApiResponse<Balance> response = bucketService.updateBucketBalance(userName, balance, serviceId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, response.getStatus());
    }

    @Test
    void testTerminateSessions_Success() {
        String userName = "testuser";
        String sessionId = "session-123";

        UserSessionData userData = createUserData(userName);
        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(coaService.clearAllSessionsAndSendCOA(any(), eq(userName), eq(sessionId)))
            .thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class), eq(userName)))
            .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<Balance> response = bucketService.terminateSessions(userName, sessionId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.OK, response.getStatus());
        verify(coaService).clearAllSessionsAndSendCOA(any(), eq(userName), eq(sessionId));
    }

    @Test
    void testTerminateSessions_COAServiceError() {
        String userName = "testuser";
        String sessionId = "session-123";

        UserSessionData userData = createUserData(userName);
        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(coaService.clearAllSessionsAndSendCOA(any(), eq(userName), eq(sessionId)))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("COA error")));

        ApiResponse<Balance> response = bucketService.terminateSessions(userName, sessionId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, response.getStatus());
    }

    @Test
    void testUpdateUserStatus_ToActive() {
        String userName = "testuser";
        String newStatus = "ACTIVE";

        UserSessionData userData = createUserData(userName);
        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class), eq(userName)))
            .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<String> response = bucketService.updateUserStatus(userName, newStatus)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("ACTIVE", userData.getUserStatus());
    }

    @Test
    void testUpdateUserStatus_ToBarred() {
        String userName = "testuser";
        String newStatus = "BARRED";

        UserSessionData userData = createUserData(userName);
        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().item(userData));
        when(cacheClient.updateUserAndRelatedCaches(eq(userName), any(UserSessionData.class), eq(userName)))
            .thenReturn(Uni.createFrom().voidItem());

        ApiResponse<String> response = bucketService.updateUserStatus(userName, newStatus)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("BARRED", userData.getUserStatus());
    }

    @Test
    void testUpdateUserStatus_UserNotFound() {
        String userName = "testuser";
        String newStatus = "ACTIVE";

        when(cacheClient.getUserData(userName)).thenReturn(Uni.createFrom().nullItem());

        ApiResponse<String> response = bucketService.updateUserStatus(userName, newStatus)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
    }

    @Test
    void testUpdateUserStatus_CacheError() {
        String userName = "testuser";
        String newStatus = "ACTIVE";

        when(cacheClient.getUserData(userName))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache error")));

        ApiResponse<String> response = bucketService.updateUserStatus(userName, newStatus)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, response.getStatus());
    }

    private Balance createBalance() {
        Balance balance = new Balance();
        balance.setBucketId("100");
        balance.setServiceId("100");
        balance.setQuota(1000L);
        balance.setInitialBalance(2000L);
        balance.setServiceExpiry(LocalDateTime.now().plusDays(30));
        balance.setBucketExpiryDate(LocalDateTime.now().plusDays(60));
        return balance;
    }

    private UserSessionData createUserData(String userName) {
        List<Balance> balances = new ArrayList<>();
        balances.add(createBalance());

        return UserSessionData.builder()
            .userName(userName)
            .balance(balances)
            .sessions(new ArrayList<>())
            .concurrency(2L)
            .userStatus("ACTIVE")
            .build();
    }
}
