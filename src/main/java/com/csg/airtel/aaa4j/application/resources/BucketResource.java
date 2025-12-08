package com.csg.airtel.aaa4j.application.resources;


import com.csg.airtel.aaa4j.domain.model.session.Balance;
import com.csg.airtel.aaa4j.domain.service.BucketService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.jboss.logging.Logger;


@Path("/cache")
@ApplicationScoped
public class BucketResource {
    private static final Logger log = Logger.getLogger(BucketResource.class);
    private final BucketService bucketService;

    @Inject
    public BucketResource(BucketService bucketService) {
        this.bucketService = bucketService;
    }

    @PATCH
    @Path("/addBucket/{userName}")
    @Timed(name = "process_time", description = "Time to process request")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> addBucket(@PathParam("userName") String userName, Balance balance) {
        log.infof("Adding bucket  Start %s", userName);
        return bucketService.addBucketBalance(userName, balance)
                .onItem().transform(apiResponse -> {
                    log.infof("Adding bucket  Completed %s", userName);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });
    }

    @PATCH
    @Path("/updateBucket/{userName}/{serviceId}")
    @Timed(name = "process_time", description = "Time to process request")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> updateBucket(@PathParam("userName") String userName, Balance balance,@PathParam("serviceId") String serviceId) {
        log.infof("update bucket  Start %s", userName);
        return bucketService.updateBucketBalance(userName, balance,serviceId)
                .onItem().transform(apiResponse -> {
                    log.infof("update bucket  Completed %s", userName);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });

    }

    @PATCH
    @Path("/terminate-sessions/{userName}/")
    @Timed(name = "process_time", description = "Time to process request")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> terminate(@PathParam("userName") String userName) {
        log.infof("Sessions Terminate  Start %s", userName);
        return bucketService.terminateSessions(userName)
                .onItem().transform(apiResponse -> {
                    log.infof("Sessions Terminate Completed %s", userName);
                    return Response.status(apiResponse.getStatus())
                            .entity(apiResponse)
                            .build();
                });
    }
}
