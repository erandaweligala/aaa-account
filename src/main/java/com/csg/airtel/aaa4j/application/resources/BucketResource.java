package com.csg.airtel.aaa4j.application.resources;


import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.model.session.BalanceWrapper;
import com.csg.airtel.aaa4j.domain.service.BucketService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;


@Path("/cache")
@ApplicationScoped
public class BucketResource {
    private static final Logger log = Logger.getLogger(BucketResource.class);
    private static final String M_ADD = "addBucket";
    private static final String M_UPDATE = "updateBucket";
    private static final String M_TERMINATE = "terminateViaHttp";
    private static final String M_STATUS = "userStatusUpdate";
    private static final String M_SVC_STATUS = "serviceStatusUpdate";
    private final BucketService bucketService;

    public BucketResource(BucketService bucketService) {
        this.bucketService = bucketService;
    }

    @PATCH
    @Path("/addBucket/{userName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> addBucket(@PathParam("userName") String userName, BalanceWrapper balance) {
        LoggingUtil.logInfo(log, M_ADD, "Adding bucket  Start %s", userName);
        return bucketService.addBucketBalance(userName, balance)
                .onItem().transform(apiResponse -> {
                    LoggingUtil.logInfo(log, M_ADD, "Adding bucket  Completed %s", userName);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });
    }

    @PATCH
    @Path("/updateBucket/{userName}/{serviceId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> updateBucket(@PathParam("userName") String userName, Balance balance,@PathParam("serviceId") String serviceId) {
        LoggingUtil.logInfo(log, M_UPDATE, "update bucket  Start %s", userName);
        return bucketService.updateBucketBalance(userName, balance,serviceId)
                .onItem().transform(apiResponse -> {
                    LoggingUtil.logInfo(log, M_UPDATE, "update bucket  Completed %s", userName);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });

    }
/*
    @PATCH
    @Path("/terminate-sessions/{userName}/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> terminate(@PathParam("userName") String userName,@PathParam("sessionId") String sessionId) {
        log.infof("Sessions Terminate  Start %s", userName);
        return bucketService.terminateSessions(userName,sessionId)
                .onItem().transform(apiResponse -> {
                    log.infof("Sessions Terminate Completed %s", userName);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });
    }

 */

    /**
     * Terminate sessions via HTTP-based CoA disconnect (non-blocking, no Kafka overhead).
     * Sends CoA disconnect directly to NAS via HTTP POST.
     * After receiving ACK, sessions are automatically cleared from cache.
     *
     * @param userName the username
     * @param sessionId specific session to disconnect (or "all" for all sessions)
     * @return Response with operation result
     */
    @PATCH
    @Path("/terminate-sessions/{userName}/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> terminateViaHttp(@PathParam("userName") String userName,
                                          @PathParam("sessionId") String sessionId) {
        LoggingUtil.logInfo(log, M_TERMINATE, "HTTP CoA disconnect started for user: %s, sessionId: %s", userName, sessionId);

        // Convert "all" to null for disconnecting all sessions
        String sessionIdParam = "all".equalsIgnoreCase(sessionId) ? null : sessionId;

        return bucketService.terminateSessionsViaHttp(userName, sessionIdParam)
                .onItem().transform(apiResponse -> {
                    LoggingUtil.logInfo(log, M_TERMINATE, "HTTP CoA disconnect completed for user: %s", userName);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });
    }

    @PATCH
    @Path("/patchStatus/{userName}/{status}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> userStatusUpdate(@PathParam("userName") String userName,@PathParam("status") String status) {
        LoggingUtil.logInfo(log, M_STATUS, "Update User Status  Start %s", userName);
        return bucketService.updateUserStatus(userName,status)
                .onItem().transform(apiResponse -> {
                    LoggingUtil.logInfo(log, M_STATUS, "Update User Status Completed %s", userName);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });
    }

    @PATCH
    @Path("/patchServiceStatus/{userName}/{serviceId}/{status}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> serviceStatusUpdate(@PathParam("userName") String userName,
                                              @PathParam("serviceId") String serviceId,
                                              @PathParam("status") String status) {
        LoggingUtil.logInfo(log, M_SVC_STATUS, "Update Service Status Start user: %s, serviceId: %s, status: %s", userName, serviceId, status);
        return bucketService.updateServiceStatus(userName, serviceId, status)
                .onItem().transform(apiResponse -> {
                    LoggingUtil.logInfo(log, M_SVC_STATUS, "Update Service Status Completed user: %s, serviceId: %s", userName, serviceId);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });
    }
}
