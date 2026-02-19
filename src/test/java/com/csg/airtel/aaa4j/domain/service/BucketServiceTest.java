package com.csg.airtel.aaa4j.domain.service;

import com.csg.airtel.aaa4j.domain.model.response.ApiResponse;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.BalanceWrapper;
import com.csg.airtel.aaa4j.domain.model.session.UserSessionData;
import com.csg.airtel.aaa4j.external.clients.CacheClient;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BucketServiceTest {

    @Mock
    CacheClient cacheClient;

    @Mock
    COAService coaService;

    @InjectMocks
    BucketService bucketService;

    private final String USER_NAME = "testUser";
    private final String SERVICE_ID = "service123";

    @Test
    @DisplayName("addBucketBalance - Success for New User")
    void testAddBucketBalance_NewUser() {
        // 1. Setup Data
        Balance balance = new Balance();
        balance.setBucketUsername("group1");
        balance.setGroup(true);
        BalanceWrapper wrapper = new BalanceWrapper();
        wrapper.setBalance(balance);

        // 2. Mocking
        doReturn(Uni.createFrom().nullItem())
                .when(cacheClient).getUserData(USER_NAME);

        // FIX: Changed from .item(...) to .voidItem()
        doReturn(Uni.createFrom().voidItem())
                .when(cacheClient).updateUserAndRelatedCaches(anyString(), any(), anyString());

        // 3. Execute
        ApiResponse<Balance> response = bucketService.addBucketBalance(USER_NAME, wrapper)
                .await().indefinitely();

        // 4. Verify
        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("Bucket Added Successfully", response.getMessage());
        verify(cacheClient).updateUserAndRelatedCaches(eq(USER_NAME), any(UserSessionData.class), eq(USER_NAME));
    }

    @Test
    @DisplayName("addBucketBalance - Success for Existing User and Clean Expired")
    void testAddBucketBalance_ExistingUser_CleansExpired() {
        // 1. Prepare expired and non-expired balances
        Balance expired = new Balance();
        expired.setBucketId("exp1");
        // This will trigger the serviceExpiry check
        expired.setServiceExpiry(LocalDateTime.now().minusDays(1));

        Balance valid = new Balance();
        valid.setBucketId("val1");
        // This remains valid
        valid.setBucketExpiryDate(LocalDateTime.now().plusDays(1));

        UserSessionData existingData = UserSessionData.builder()
                .userName(USER_NAME)
                .balance(List.of(expired, valid))
                .build();

        Balance newBalance = new Balance();
        newBalance.setBucketId("new1");
        BalanceWrapper wrapper = new BalanceWrapper();
        wrapper.setBalance(newBalance);

        // 2. Fix: Use doReturn to handle the Uni generic type
        doReturn(Uni.createFrom().item(existingData))
                .when(cacheClient).getUserData(USER_NAME);

        doReturn(Uni.createFrom().item(existingData))
                .when(cacheClient).updateUserAndRelatedCaches(anyString(), any(), anyString());

        // 3. Execute
        bucketService.addBucketBalance(USER_NAME, wrapper).await().indefinitely();

        // 4. Verify that only 'valid' and 'newBalance' were kept (total 2)
        verify(cacheClient).updateUserAndRelatedCaches(
                eq(USER_NAME),
                argThat(data -> data.getBalance().size() == 2 &&
                        data.getBalance().stream().noneMatch(b -> b.getBucketId().equals("exp1"))),
                eq(USER_NAME)
        );
    }
    @Test
    @DisplayName("updateBucketBalance - Success")
    void testUpdateBucketBalance_Success() {
        // 1. Arrange
        Balance existingBalance = new Balance();
        existingBalance.setServiceId(SERVICE_ID);
        existingBalance.setBucketId("old-bucket");

        UserSessionData existingData = UserSessionData.builder()
                .userName(USER_NAME)
                .balance(new ArrayList<>(List.of(existingBalance)))
                .build();

        Balance updatedBalance = new Balance();
        updatedBalance.setServiceId(SERVICE_ID);
        updatedBalance.setBucketId("new-bucket");

        // Fix: Use doReturn instead of when(...).thenReturn(...)
        doReturn(Uni.createFrom().item(existingData))
                .when(cacheClient).getUserData(USER_NAME);

        doReturn(Uni.createFrom().voidItem())
                .when(cacheClient).updateUserAndRelatedCaches(anyString(), any(), anyString());
        // 2. Act
        ApiResponse<Balance> response = bucketService.updateBucketBalance(USER_NAME, updatedBalance, SERVICE_ID)
                .await().indefinitely();

        // 3. Assert
        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("Updated balance Successfully", response.getMessage());

        // Verify that the old bucket was replaced by the new one
        verify(cacheClient).updateUserAndRelatedCaches(eq(USER_NAME), argThat(data ->
                data.getBalance().size() == 1 &&
                        data.getBalance().get(0).getBucketId().equals("new-bucket")
        ), eq(USER_NAME));
    }

    @Test
    @DisplayName("updateBucketBalance - Validation Failures")
    void testUpdateBucketBalance_Validation() {
        // Missing Service ID
        ApiResponse<Balance> res1 = bucketService.updateBucketBalance(USER_NAME, new Balance(), "").await().indefinitely();
        assertEquals("Service Id is required", res1.getMessage());

        // Mismatched Service ID
        Balance b = new Balance();
        b.setServiceId("wrong");
        ApiResponse<Balance> res2 = bucketService.updateBucketBalance(USER_NAME, b, SERVICE_ID).await().indefinitely();
        assertEquals("Balance serviceId must match the provided serviceId", res2.getMessage());
    }

    @Test
    @DisplayName("updateUserStatus - Success and trigger COA")
    void testUpdateUserStatus_Success() {
        // 1. Arrange
        UserSessionData userData = UserSessionData.builder()
                .userName(USER_NAME)
                .userStatus("ACTIVE")
                .sessions(List.of(new com.csg.airtel.aaa4j.domain.model.session.Session()))
                .build();

        doReturn(Uni.createFrom().item(userData))
                .when(cacheClient).getUserData(USER_NAME);

        // FIX HERE: Change .item(userData) to .voidItem()
        doReturn(Uni.createFrom().voidItem())
                .when(cacheClient).updateUserAndRelatedCaches(anyString(), any(), anyString());

        doReturn(Uni.createFrom().item(userData))
                .when(coaService).clearAllSessionsAndSendCOA(any(), anyString(), any());

        // 2. Act
        ApiResponse<String> response = bucketService.updateUserStatus(USER_NAME, "BARRED")
                .await().indefinitely();

        // 3. Assert
        assertEquals(Response.Status.OK, response.getStatus());
        verify(coaService).clearAllSessionsAndSendCOA(any(), eq(USER_NAME), isNull());
    }

    @Test
    @DisplayName("terminateSessionsViaHttp - User Not Found")
    void testTerminateSessions_UserNotFound() {
        when(cacheClient.getUserData(USER_NAME)).thenReturn(Uni.createFrom().nullItem());

        ApiResponse<Balance> response = bucketService.terminateSessionsViaHttp(USER_NAME, "sess1").await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals("User not found", response.getMessage());
    }

    @Test
    @DisplayName("Generic Failure Recovery (onFailure)")
    void testOnFailureRecovery() {
        when(cacheClient.getUserData(USER_NAME)).thenReturn(Uni.createFrom().failure(new RuntimeException("Database Down")));

        ApiResponse<Balance> response = bucketService.addBucketBalance(USER_NAME, new BalanceWrapper()).await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertTrue(response.getMessage().contains("Database Down"));
    }

    @Test
    @DisplayName("terminateSessions - Success")
    void testTerminateSessions_Success() {
        // 1. Arrange
        String sessionId = "session-123";
        UserSessionData userData = UserSessionData.builder()
                .userName(USER_NAME)
                .sessions(new ArrayList<>()) // No active sessions after termination
                .build();

        // Mock initial data fetch
        doReturn(Uni.createFrom().item(userData))
                .when(cacheClient).getUserData(USER_NAME);

        // Mock COA service - Important: must return a Uni containing UserSessionData
        doReturn(Uni.createFrom().item(userData))
                .when(coaService).clearAllSessionsAndSendCOA(any(), eq(USER_NAME), eq(sessionId));

        // 2. Act
        ApiResponse<Balance> response = bucketService.terminateSessions(USER_NAME, sessionId)
                .await().indefinitely();

        // 3. Assert
        assertNotNull(response);
        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("Terminated successfully", response.getMessage());

        verify(coaService).clearAllSessionsAndSendCOA(any(), eq(USER_NAME), eq(sessionId));
    }

    @Test
    @DisplayName("terminateSessions - User Not Found")
    void testTerminateSessions_UserNotFound2() {
        // 1. Arrange
        doReturn(Uni.createFrom().nullItem())
                .when(cacheClient).getUserData(USER_NAME);

        // 2. Act
        ApiResponse<Balance> response = bucketService.terminateSessions(USER_NAME, "any-session")
                .await().indefinitely();

        // 3. Assert
        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals("User not found", response.getMessage());

        // Verify COA was NEVER called
        verify(coaService, never()).clearAllSessionsAndSendCOA(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("removeExpiredBalances logic - Filter varied expiry scenarios")
    void testRemoveExpiredBalances_Logic() {
        // 1. Arrange
        LocalDateTime now = LocalDateTime.now();

        // Balance 1: Expired via serviceExpiry
        Balance b1 = new Balance();
        b1.setBucketId("B1");
        b1.setServiceExpiry(now.minusMinutes(1));

        // Balance 2: Expired via bucketExpiryDate
        Balance b2 = new Balance();
        b2.setBucketId("B2");
        b2.setBucketExpiryDate(now.minusMinutes(1));

        // Balance 3: Valid (not expired)
        Balance b3 = new Balance();
        b3.setBucketId("B3");
        b3.setServiceExpiry(now.plusDays(1));
        b3.setBucketExpiryDate(now.plusDays(1));

        // Balance 4: Valid (null dates)
        Balance b4 = new Balance();
        b4.setBucketId("B4");

        UserSessionData existingData = UserSessionData.builder()
                .userName(USER_NAME)
                .balance(List.of(b1, b2, b3, b4))
                .build();

        Balance newBalance = new Balance();
        newBalance.setBucketId("NEW");
        BalanceWrapper wrapper = new BalanceWrapper();
        wrapper.setBalance(newBalance);

        doReturn(Uni.createFrom().item(existingData))
                .when(cacheClient).getUserData(USER_NAME);

        doReturn(Uni.createFrom().voidItem())
                .when(cacheClient).updateUserAndRelatedCaches(anyString(), any(), anyString());

        // 2. Act
        bucketService.addBucketBalance(USER_NAME, wrapper).await().indefinitely();

        // 3. Assert & Verify filtering
        verify(cacheClient).updateUserAndRelatedCaches(eq(USER_NAME), argThat(data -> {
            List<Balance> result = data.getBalance();
            // Should contain B3, B4, and the NEW balance. B1 and B2 should be removed.
            boolean containsValid = result.stream().anyMatch(b -> b.getBucketId().equals("B3"));
            boolean containsNulls = result.stream().anyMatch(b -> b.getBucketId().equals("B4"));
            boolean containsNew = result.stream().anyMatch(b -> b.getBucketId().equals("NEW"));
            boolean removedB1 = result.stream().noneMatch(b -> b.getBucketId().equals("B1"));
            boolean removedB2 = result.stream().noneMatch(b -> b.getBucketId().equals("B2"));

            return result.size() == 3 && containsValid && containsNulls && containsNew && removedB1 && removedB2;
        }), eq(USER_NAME));
    }

    @Test
    @DisplayName("updateUserStatus - Validation Failures")
    void testUpdateUserStatus_Validation() {
        // Test Null Username
        ApiResponse<String> res1 = bucketService.updateUserStatus(null, "ACTIVE").await().indefinitely();
        assertEquals("Username is required", res1.getMessage());

        // Test Invalid Status value
        ApiResponse<String> res2 = bucketService.updateUserStatus(USER_NAME, "PENDING").await().indefinitely();
        assertEquals("Invalid status. Must be 'ACTIVE' or 'BARRED'", res2.getMessage());
    }

    @Test
    @DisplayName("updateUserStatus - User Not Found")
    void testUpdateUserStatus_UserNotFound() {
        doReturn(Uni.createFrom().nullItem())
                .when(cacheClient).getUserData(USER_NAME);

        ApiResponse<String> response = bucketService.updateUserStatus(USER_NAME, "ACTIVE").await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals("User not found", response.getMessage());
    }

    @Test
    @DisplayName("updateUserStatus - Success without COA (No Sessions)")
    void testUpdateUserStatus_NoSessions() {
        // 1. Arrange: User exists but has null or empty sessions
        UserSessionData userData = UserSessionData.builder()
                .userName(USER_NAME)
                .userStatus("ACTIVE")
                .sessions(Collections.emptyList())
                .build();

        doReturn(Uni.createFrom().item(userData))
                .when(cacheClient).getUserData(USER_NAME);

        doReturn(Uni.createFrom().voidItem())
                .when(cacheClient).updateUserAndRelatedCaches(anyString(), any(), anyString());

        // 2. Act
        ApiResponse<String> response = bucketService.updateUserStatus(USER_NAME, "BARRED").await().indefinitely();

        // 3. Assert
        assertEquals(Response.Status.OK, response.getStatus());
        // Verify COA was NOT called because sessions list was empty
        verify(coaService, times(0)).clearAllSessionsAndSendCOA(any(), any(), any());
    }

    @Test
    @DisplayName("updateUserStatus - Failure Recovery")
    void testUpdateUserStatus_Failure() {
        // Arrange: Simulate a database/cache failure
        doReturn(Uni.createFrom().failure(new RuntimeException("Connection Timeout")))
                .when(cacheClient).getUserData(USER_NAME);

        // Act
        ApiResponse<String> response = bucketService.updateUserStatus(USER_NAME, "ACTIVE").await().indefinitely();

        // Assert
        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertTrue(response.getMessage().contains("Connection Timeout"));
    }

    // --- updateServiceStatus tests ---

    @Test
    @DisplayName("updateServiceStatus - Success with COA")
    void testUpdateServiceStatus_Success() {
        // 1. Arrange
        Balance balance = new Balance();
        balance.setServiceId(SERVICE_ID);
        balance.setServiceStatus("Active");

        UserSessionData userData = UserSessionData.builder()
                .userName(USER_NAME)
                .balance(new ArrayList<>(List.of(balance)))
                .sessions(List.of(new com.csg.airtel.aaa4j.domain.model.session.Session()))
                .build();

        doReturn(Uni.createFrom().item(userData))
                .when(cacheClient).getUserData(USER_NAME);

        doReturn(Uni.createFrom().voidItem())
                .when(cacheClient).updateUserAndRelatedCaches(anyString(), any(), anyString());

        doReturn(Uni.createFrom().item(userData))
                .when(coaService).clearAllSessionsAndSendCOA(any(), anyString(), any());

        // 2. Act
        ApiResponse<String> response = bucketService.updateServiceStatus(USER_NAME, SERVICE_ID, "Barred")
                .await().indefinitely();

        // 3. Assert
        assertEquals(Response.Status.OK, response.getStatus());
        assertTrue(response.getMessage().contains("Service status updated successfully"));
        assertTrue(response.getMessage().contains("Active"));
        assertTrue(response.getMessage().contains("Barred"));

        // Verify cache was updated with the new service status
        verify(cacheClient).updateUserAndRelatedCaches(eq(USER_NAME), argThat(data -> {
            Balance updatedBalance = data.getBalance().stream()
                    .filter(b -> SERVICE_ID.equals(b.getServiceId()))
                    .findFirst().orElse(null);
            return updatedBalance != null && "Barred".equals(updatedBalance.getServiceStatus());
        }), eq(USER_NAME));

        // Verify COA was sent
        verify(coaService).clearAllSessionsAndSendCOA(any(), eq(USER_NAME), isNull());
    }

    @Test
    @DisplayName("updateServiceStatus - Success without COA (No Sessions)")
    void testUpdateServiceStatus_NoSessions() {
        // 1. Arrange
        Balance balance = new Balance();
        balance.setServiceId(SERVICE_ID);
        balance.setServiceStatus("Active");

        UserSessionData userData = UserSessionData.builder()
                .userName(USER_NAME)
                .balance(new ArrayList<>(List.of(balance)))
                .sessions(Collections.emptyList())
                .build();

        doReturn(Uni.createFrom().item(userData))
                .when(cacheClient).getUserData(USER_NAME);

        doReturn(Uni.createFrom().voidItem())
                .when(cacheClient).updateUserAndRelatedCaches(anyString(), any(), anyString());

        // 2. Act
        ApiResponse<String> response = bucketService.updateServiceStatus(USER_NAME, SERVICE_ID, "Suspended")
                .await().indefinitely();

        // 3. Assert
        assertEquals(Response.Status.OK, response.getStatus());
        verify(coaService, never()).clearAllSessionsAndSendCOA(any(), anyString(), any());
    }

    @Test
    @DisplayName("updateServiceStatus - Validation Failures")
    void testUpdateServiceStatus_Validation() {
        // Null username
        ApiResponse<String> res1 = bucketService.updateServiceStatus(null, SERVICE_ID, "Active").await().indefinitely();
        assertEquals("Username is required", res1.getMessage());

        // Blank username
        ApiResponse<String> res2 = bucketService.updateServiceStatus("", SERVICE_ID, "Active").await().indefinitely();
        assertEquals("Username is required", res2.getMessage());

        // Null serviceId
        ApiResponse<String> res3 = bucketService.updateServiceStatus(USER_NAME, null, "Active").await().indefinitely();
        assertEquals("Service ID is required", res3.getMessage());

        // Blank status
        ApiResponse<String> res4 = bucketService.updateServiceStatus(USER_NAME, SERVICE_ID, "").await().indefinitely();
        assertEquals("Status is required", res4.getMessage());
    }

    @Test
    @DisplayName("updateServiceStatus - User Not Found")
    void testUpdateServiceStatus_UserNotFound() {
        doReturn(Uni.createFrom().nullItem())
                .when(cacheClient).getUserData(USER_NAME);

        ApiResponse<String> response = bucketService.updateServiceStatus(USER_NAME, SERVICE_ID, "Active")
                .await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals("User not found", response.getMessage());
    }

    @Test
    @DisplayName("updateServiceStatus - No Balances Found")
    void testUpdateServiceStatus_NoBalances() {
        UserSessionData userData = UserSessionData.builder()
                .userName(USER_NAME)
                .balance(Collections.emptyList())
                .build();

        doReturn(Uni.createFrom().item(userData))
                .when(cacheClient).getUserData(USER_NAME);

        ApiResponse<String> response = bucketService.updateServiceStatus(USER_NAME, SERVICE_ID, "Active")
                .await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertEquals("No balances found for user", response.getMessage());
    }

    @Test
    @DisplayName("updateServiceStatus - ServiceId Not Found in Balances")
    void testUpdateServiceStatus_ServiceIdNotFound() {
        Balance balance = new Balance();
        balance.setServiceId("other-service");
        balance.setServiceStatus("Active");

        UserSessionData userData = UserSessionData.builder()
                .userName(USER_NAME)
                .balance(new ArrayList<>(List.of(balance)))
                .build();

        doReturn(Uni.createFrom().item(userData))
                .when(cacheClient).getUserData(USER_NAME);

        ApiResponse<String> response = bucketService.updateServiceStatus(USER_NAME, SERVICE_ID, "Barred")
                .await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertTrue(response.getMessage().contains(SERVICE_ID));
        assertTrue(response.getMessage().contains("not found"));
    }

    @Test
    @DisplayName("updateServiceStatus - Failure Recovery")
    void testUpdateServiceStatus_Failure() {
        doReturn(Uni.createFrom().failure(new RuntimeException("Redis Timeout")))
                .when(cacheClient).getUserData(USER_NAME);

        ApiResponse<String> response = bucketService.updateServiceStatus(USER_NAME, SERVICE_ID, "Active")
                .await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
        assertTrue(response.getMessage().contains("Redis Timeout"));
    }
}