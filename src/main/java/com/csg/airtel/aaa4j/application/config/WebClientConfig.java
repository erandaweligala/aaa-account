package com.csg.airtel.aaa4j.application.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for WebClient connection pool settings optimized for 1500 TPS.
 * Pool sizing rationale:
 * - HTTP/1.1 pool: 30 connections per pod for burst CoA disconnect handling
 * - HTTP/2 pool: 30 connections with 150 streams each = 4,500 concurrent requests per pod
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
