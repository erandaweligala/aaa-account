package com.csg.airtel.aaa4j.application.resources;

import com.csg.airtel.aaa4j.domain.model.response.ApiResponse;
import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.service.BucketService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.jboss.logging.Logger;


@Path("/cache")
@ApplicationScoped
public class BucketResource {
    private static final Logger log = Logger.getLogger(BucketResource.class);
    private final BucketService bucketService;

    public BucketResource(BucketService bucketService) {
        this.bucketService = bucketService;
    }

    @PATCH
    @Path("/addBucket/{userName}")
    @Timed(name = "process_time", description = "Time to process request")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ApiResponse<Balance>> addBucket(@PathParam("userName") String userName, Balance balance) {
        log.infof("Adding bucket  Start %s", userName);
        Uni<ApiResponse<Balance>> apiResponseUni = bucketService.addBucketBalance(userName, balance);
        log.infof("Adding bucket  Completed %s", userName);
        return apiResponseUni;
    }

    @PATCH
    @Path("/updateBucket/{userName}/{serviceId}")
    @Timed(name = "process_time", description = "Time to process request")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<ApiResponse<Balance>> updateBucket(@PathParam("userName") String userName, Balance balance,@PathParam("serviceId") String serviceId) {
        log.infof("update bucket  Start %s", userName);
        Uni<ApiResponse<Balance>> apiResponseUni = bucketService.updateBucketBalance(userName, balance,serviceId);
        log.infof("update bucket  Completed %s", userName);
        return apiResponseUni;
    }
}
