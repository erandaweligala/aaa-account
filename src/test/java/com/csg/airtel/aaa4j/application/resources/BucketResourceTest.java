package com.csg.airtel.aaa4j.application.resources;

import com.csg.airtel.aaa4j.domain.model.response.ApiResponse;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.service.BucketService;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BucketResourceTest {

    @Mock
    private BucketService bucketService;

    @InjectMocks
    private BucketResource bucketResource;

    private String userName;
    private Balance balance;
    private ApiResponse<Balance> apiResponse;

    @BeforeEach
    void setUp() {
        userName = "testuser";
        balance = new Balance();
        balance.setServiceId("SERVICE123");
        balance.setQuota(1000L);

        apiResponse = new ApiResponse<>();
        apiResponse.setTimestamp(Instant.now());
        apiResponse.setMessage("Success");
        apiResponse.setData(balance);
    }

    @Test
    void testAddBucket() {
        when(bucketService.addBucketBalance(userName, balance))
                .thenReturn(Uni.createFrom().item(apiResponse));

        Uni<ApiResponse<Balance>> result = bucketResource.addBucket(userName, balance);

        assertNotNull(result);
        ApiResponse<Balance> response = result.await().indefinitely();
        assertNotNull(response);
        assertEquals("Success", response.getMessage());
        assertEquals(balance, response.getData());

        verify(bucketService).addBucketBalance(userName, balance);
    }

    @Test
    void testAddBucketWithNullBalance() {
        ApiResponse<Balance> errorResponse = new ApiResponse<>();
        errorResponse.setMessage("Balance is required");
        errorResponse.setData(null);

        when(bucketService.addBucketBalance(userName, null))
                .thenReturn(Uni.createFrom().item(errorResponse));

        Uni<ApiResponse<Balance>> result = bucketResource.addBucket(userName, null);

        assertNotNull(result);
        ApiResponse<Balance> response = result.await().indefinitely();
        assertEquals("Balance is required", response.getMessage());
        assertNull(response.getData());

        verify(bucketService).addBucketBalance(userName, null);
    }

    @Test
    void testUpdateBucket() {
        String serviceId = "SERVICE123";
        when(bucketService.updateBucketBalance(userName, balance, serviceId))
                .thenReturn(Uni.createFrom().item(apiResponse));

        Uni<ApiResponse<Balance>> result = bucketResource.updateBucket(userName, balance, serviceId);

        assertNotNull(result);
        ApiResponse<Balance> response = result.await().indefinitely();
        assertNotNull(response);
        assertEquals("Success", response.getMessage());
        assertEquals(balance, response.getData());

        verify(bucketService).updateBucketBalance(userName, balance, serviceId);
    }

    @Test
    void testUpdateBucketWithDifferentServiceId() {
        String serviceId = "DIFFERENT_SERVICE";
        ApiResponse<Balance> errorResponse = new ApiResponse<>();
        errorResponse.setMessage("Balance serviceId must match the provided serviceId");

        when(bucketService.updateBucketBalance(userName, balance, serviceId))
                .thenReturn(Uni.createFrom().item(errorResponse));

        Uni<ApiResponse<Balance>> result = bucketResource.updateBucket(userName, balance, serviceId);

        assertNotNull(result);
        ApiResponse<Balance> response = result.await().indefinitely();
        assertEquals("Balance serviceId must match the provided serviceId", response.getMessage());

        verify(bucketService).updateBucketBalance(userName, balance, serviceId);
    }

    @Test
    void testAddBucketReturnsUni() {
        when(bucketService.addBucketBalance(anyString(), any(Balance.class)))
                .thenReturn(Uni.createFrom().item(apiResponse));

        Uni<ApiResponse<Balance>> result = bucketResource.addBucket(userName, balance);

        assertNotNull(result);
        assertTrue(result instanceof Uni);
    }

    @Test
    void testUpdateBucketReturnsUni() {
        when(bucketService.updateBucketBalance(anyString(), any(Balance.class), anyString()))
                .thenReturn(Uni.createFrom().item(apiResponse));

        Uni<ApiResponse<Balance>> result = bucketResource.updateBucket(userName, balance, "SERVICE123");

        assertNotNull(result);
        assertTrue(result instanceof Uni);
    }
}
