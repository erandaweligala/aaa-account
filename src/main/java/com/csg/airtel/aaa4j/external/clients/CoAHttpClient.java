package com.csg.airtel.aaa4j.external.clients;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.application.config.WebClientProvider;
import com.csg.airtel.aaa4j.domain.model.AccountingResponseEvent;
import com.csg.airtel.aaa4j.domain.model.coa.CoADisconnectResponse;
import com.csg.airtel.aaa4j.exception.CoADisconnectException;
import com.csg.airtel.aaa4j.exception.CoAResponseParsingException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.Json;
import io.vertx.core.json.DecodeException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * HTTP Client for sending CoA (Change of Authorization) Disconnect requests to servers.
 */
@ApplicationScoped
public class CoAHttpClient {

    private static final Logger log = Logger.getLogger(CoAHttpClient.class);
    private static final String CLASS_NAME = CoAHttpClient.class.getSimpleName();

    private final WebClientProvider webClientProvider;


    @ConfigProperty(name = "coa.nas.host")
    String host;

    @ConfigProperty(name = "coa.nas.port")
    Integer port;

    public CoAHttpClient(WebClientProvider webClientProvider) {
        this.webClientProvider = webClientProvider;
    }

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String COA_ENDPOINT = "/api/coa/";
    private static final String NAK_STATUS = "NAK";
    private static final String SESSION_DISCONNECTED_MESSAGE = "Session already disconnected (404 from NAS)";

    /**
     * Send CoA Disconnect request to  server via HTTP.
     *
     * @param request CoA disconnect request with session details
     * @return Uni containing the disconnect response with ACK/NAK status
     */
    public Uni<CoADisconnectResponse> sendDisconnect(AccountingResponseEvent request) {
        LoggingUtil.logDebug(log, CLASS_NAME, "sendDisconnect", "Sending CoA disconnect request: sessionId=%s", request.sessionId());

        String jsonBody = Json.encode(request);
        io.vertx.core.buffer.Buffer buffer = io.vertx.core.buffer.Buffer.buffer(jsonBody);
        String sessionId = request.sessionId();

        return Uni.createFrom().emitter(emitter ->
            webClientProvider.getClient()
                    .post(port, host, COA_ENDPOINT)
                    .putHeader("Content-Type", CONTENT_TYPE_JSON)
                    .putHeader("Accept", CONTENT_TYPE_JSON)
                    .sendBuffer(buffer, ar -> {
                        if (ar.succeeded()) {
                            try {
                                CoADisconnectResponse response = handleHttpResponse(ar.result(), sessionId);
                                emitter.complete(response);
                            } catch (Exception e) {
                                if (e instanceof CoAResponseParsingException || e instanceof CoADisconnectException) {
                                    emitter.fail(e);
                                } else {
                                    LoggingUtil.logError(log, CLASS_NAME, "sendDisconnect", e, "Unexpected error handling CoA response for session: %s", sessionId);
                                    emitter.fail(new CoAResponseParsingException(
                                        "Unexpected error handling CoA response: " + e.getMessage(), e));
                                }
                            }
                        } else {
                            LoggingUtil.logError(log, CLASS_NAME, "sendDisconnect", ar.cause(), "HTTP request failed for session: %s", sessionId);
                            emitter.fail(new CoADisconnectException(
                                "HTTP request failed for session: " + sessionId,
                                Response.Status.SERVICE_UNAVAILABLE,
                                ar.cause()));
                        }
                    })
        );
    }

    /**
     * Handle HTTP response and convert to CoADisconnectResponse.
     */
    private CoADisconnectResponse handleHttpResponse(io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> response,
                                                      String sessionId) {
        int statusCode = response.statusCode();

        if (statusCode >= 200 && statusCode < 300) {
            return parseSuccessResponse(response, sessionId);
        }

        if (statusCode == 404) {
            return handle404Response(sessionId);
        }

        return handleErrorResponse(response, statusCode);
    }

    /**
     * Parse successful 2xx response.
     */
    private CoADisconnectResponse parseSuccessResponse(io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> response,
                                                         String sessionId) {
        try {
            CoADisconnectResponse coaResponse = response.bodyAsJson(CoADisconnectResponse.class);
            LoggingUtil.logDebug(log, CLASS_NAME, "parseSuccessResponse", "CoA disconnect response received: status=%s, sessionId=%s",
                    coaResponse.status(), coaResponse.sessionId());
            return coaResponse;
        } catch (DecodeException e) {
            String errorMsg = "Failed to parse CoA response for session " + sessionId + ": " + response.bodyAsString();
            LoggingUtil.logError(log, CLASS_NAME, "parseSuccessResponse", e, errorMsg);
            throw new CoAResponseParsingException(errorMsg, e);
        }
    }

    /**
     * Handle 404 response - session already disconnected.
     */
    private CoADisconnectResponse handle404Response(String sessionId) {
        LoggingUtil.logWarn(log, CLASS_NAME, "handle404Response", "CoA disconnect received 404 for session %s - session already disconnected or doesn't exist, treating as success",
                sessionId);
        return new CoADisconnectResponse(NAK_STATUS, sessionId, SESSION_DISCONNECTED_MESSAGE);
    }

    /**
     * Handle error responses with non-success status codes.
     */
    private CoADisconnectResponse handleErrorResponse(io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> response,
                                                       int statusCode) {
        String errorMsg = "CoA disconnect failed with status " + statusCode + ": " + response.bodyAsString();
        LoggingUtil.logWarn(log, CLASS_NAME, "handleErrorResponse", errorMsg);
        throw new CoADisconnectException(errorMsg, Response.Status.fromStatusCode(statusCode));
    }
}
