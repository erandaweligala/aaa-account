package com.csg.airtel.aaa4j.application.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for WebClient connection pool settings optimized for high throughput.
 * Pool sizing rationale:
 * - HTTP/1.1 pool: 250 connections (fallback for non-HTTP/2 servers)
 * - HTTP/2 pool: 250 connections with 100 streams each = 25,000 concurrent requests
 * - With HTTP/2 multiplexing, fewer connections needed for high throughput
 */
@ConfigMapping(prefix = "webclient")
public interface WebClientConfig {

    /**
     * HTTP/1.1 connection pool size (fallback for non-HTTP/2 servers)
     */
    @WithDefault("250")
    int maxPoolSize();

    /**
     * Connection timeout in milliseconds
     */
    @WithDefault("5000")
    int connectTimeout();

    /**
     * Idle timeout in milliseconds - keep connections warm
     */
    @WithDefault("60000")
    int idleTimeout();

    /**
     * Keep connections alive for reuse
     */
    @WithDefault("true")
    boolean keepAlive();

    /**
     * Enable HTTP pipelining for HTTP/1.1
     */
    @WithDefault("true")
    boolean pipelining();

    /**
     * HTTP pipelining limit
     */
    @WithDefault("10")
    int pipeliningLimit();

    /**
     * HTTP/2 connection pool size (primary for high throughput)
     */
    @WithDefault("250")
    int http2MaxPoolSize();

    /**
     * HTTP/2 streams per connection (multiplexing)
     */
    @WithDefault("100")
    int http2MultiplexingLimit();

    /**
     * HTTP/2 connection keep-alive interval in seconds
     */
    @WithDefault("60")
    int http2KeepAliveTimeout();
}
