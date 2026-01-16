package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.domain.model.coa.CoADisconnectRequest;
import com.csg.airtel.aaa4j.domain.model.coa.CoADisconnectResponse;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST Client for sending CoA (Change of Authorization) Disconnect requests to NAS servers via HTTP.
 * This client provides a lightweight, non-blocking alternative to Kafka-based CoA messaging.
 *
 * Configuration:
 * - quarkus.rest-client."com.csg.airtel.aaa4j.external.clients.CoAHttpClient".url
 *
 * Features:
 * - Non-blocking reactive operations using Mutiny Uni
 * - No circuit breaker or retry overhead for minimal latency
 * - Direct HTTP POST to NAS endpoint
 */
@RegisterRestClient(configKey = "coa-http-client")
@Path("/coa")
public interface CoAHttpClient {

    /**
     * Send CoA Disconnect request to NAS server via HTTP.
     * This is a non-blocking operation that returns immediately.
     *
     * @param request CoA disconnect request with session details
     * @return Uni containing the disconnect response with ACK/NACK status
     */
    @POST
    @Path("/disconnect")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<CoADisconnectResponse> sendDisconnect(CoADisconnectRequest request);
}
