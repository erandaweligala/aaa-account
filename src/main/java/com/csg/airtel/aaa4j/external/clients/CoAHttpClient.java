package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.config.WebClientProvider;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.coa.CoADisconnectRequest;
import com.csg.airtel.aaa4j.domain.model.coa.CoADisconnectResponse;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.Json;
import io.vertx.ext.web.client.WebClient;
import io.vertx.mutiny.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * HTTP Client for sending CoA (Change of Authorization) Disconnect requests to NAS servers.
 * This implementation uses Vert.x WebClient for non-blocking, reactive HTTP operations.
 *
 * Configuration:
 * - coa.nas.url: NAS server endpoint URL
 * - coa.connect.timeout: Connection timeout in milliseconds
 * - coa.read.timeout: Read timeout in milliseconds
 *
 * Features:
 * - Non-blocking reactive operations using Mutiny Uni
 * - Direct HTTP POST to NAS endpoint
 * - Minimal latency with no circuit breaker or retry overhead
 */
@ApplicationScoped
public class CoAHttpClient {

    private static final Logger log = Logger.getLogger(CoAHttpClient.class);

    private final WebClientProvider webClientProvider;
    private final String nasHost;
    private final int nasPort;
    private final boolean isHttps;

    public CoAHttpClient(WebClientProvider webClientProvider, String nasHost, int nasPort, boolean isHttps) {
        this.webClientProvider = webClientProvider;
        this.nasHost = nasHost;
        this.nasPort = nasPort;
        this.isHttps = isHttps;
    }




    /**
     * Send CoA Disconnect request to NAS server via HTTP.
     * This is a non-blocking operation that returns immediately.
     *
     * @param request CoA disconnect request with session details
     * @return Uni containing the disconnect response with ACK/NACK status
     */
    public Uni<CoADisconnectResponse> sendDisconnect(AccountingResponseEvent request) {
        log.debugf("Sending CoA disconnect request: sessionId=%s",
                request.sessionId());
        WebClient webClient = webClientProvider.getClient();
        return Uni.createFrom().emitter(emitter -> {
            try {
                // Serialize request to JSON
                String jsonBody = Json.encode(request);

                // Create request
                var httpRequest = webClient
                        .post(nasPort, nasHost, "/coa/disconnect")
                        .putHeader("Content-Type", "application/json")
                        .putHeader("Accept", "application/json");

                // Send request
                httpRequest.sendBuffer(io.vertx.core.buffer.Buffer.buffer(jsonBody), ar -> {
                    if (ar.succeeded()) {
                        var response = ar.result();
                        int statusCode = response.statusCode();

                        if (statusCode >= 200 && statusCode < 300) {
                            try {
                                // Parse response
                                CoADisconnectResponse coaResponse = response.bodyAsJson(CoADisconnectResponse.class);
                                log.debugf("CoA disconnect response received: status=%s, sessionId=%s",
                                        coaResponse.status(), coaResponse.sessionId());
                                emitter.complete(coaResponse);
                            } catch (Exception e) {
                                log.errorf(e, "Failed to parse CoA response for session: %s", request.sessionId());
                                emitter.fail(e);
                            }
                        } else {
                            String errorMsg = String.format("CoA disconnect failed with status %d: %s",
                                    statusCode, response.bodyAsString());
                            log.warnf(errorMsg);
                            emitter.fail(new RuntimeException(errorMsg));
                        }
                    } else {
                        log.errorf(ar.cause(), "HTTP request failed for session: %s", request.sessionId());
                        emitter.fail(ar.cause());
                    }
                });
            } catch (Exception e) {
                log.errorf(e, "Error creating CoA disconnect request for session: %s", request.sessionId());
                emitter.fail(e);
            }
        });
    }
}
