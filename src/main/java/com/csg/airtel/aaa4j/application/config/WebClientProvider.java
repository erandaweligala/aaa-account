package com.csg.airtel.aaa4j.application.config;


import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WebClientProvider {

    private final Vertx vertx;
    private final WebClientConfig config;
    private WebClient webClient;

    @Inject
    public WebClientProvider(Vertx vertx, WebClientConfig config) {
        this.vertx = vertx;
        this.config = config;
    }

    /**
     * Initialize WebClient with connection pool settings from configuration.
     * Pool sizing rationale:
     * - HTTP/1.1 pool: configurable connections (fallback for non-HTTP/2 servers)
     * - HTTP/2 pool: configurable connections with multiplexing for concurrent requests
     * - With HTTP/2 multiplexing, fewer connections needed for high throughput
     */
    @PostConstruct
    void init() {
        WebClientOptions options = new WebClientOptions()
                .setShared(true)
                .setTcpNoDelay(true)
                .setMaxWaitQueueSize(600)
                // HTTP/1.1 connection pool (fallback)
                .setMaxPoolSize(config.maxPoolSize())
                // Connection timeout
                .setConnectTimeout(config.connectTimeout())
                // Idle timeout - keep connections warm
                .setIdleTimeout(config.idleTimeout())
                // Keep connections alive for reuse
                .setKeepAlive(config.keepAlive())
                // Enable HTTP pipelining for HTTP/1.1
                .setPipelining(config.pipelining())
                .setPipeliningLimit(config.pipeliningLimit())
                // HTTP/2 connection pool (primary for high throughput)
                .setHttp2MaxPoolSize(config.http2MaxPoolSize())
                // HTTP/2 streams per connection (multiplexing)
                .setHttp2MultiplexingLimit(config.http2MultiplexingLimit())
                // Connection keep-alive interval
                .setHttp2KeepAliveTimeout(config.http2KeepAliveTimeout());

        this.webClient = WebClient.create(vertx, options);
    }

    public WebClient getClient() {
        return webClient;
    }
}
