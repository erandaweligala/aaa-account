package com.csg.airtel.aaa4j.application.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for Oracle database connection pool.
 * Optimized for 1500 TPS across 5 pods (300 TPS/pod, 1 core each).
 * DB spec: 32 cores, 12GB RAM. Total connections: 5 pods x 30 = 150.
 */
@ConfigMapping(prefix = "pool")
public interface PoolConfig {

    /**
     * Maximum number of connections in the pool per pod.
     * For 300 TPS/pod with concurrency=24: needs headroom for concurrent DB queries.
     * 5 pods x 30 = 150 total connections (safe for 32-core DB).
     * Default: 30
     */
    @WithDefault("30")
    int maxSize();

    /**
     * Maximum time to wait for a connection from the pool.
     * Default: 5000ms (5 seconds)
     */
    @WithDefault("5000")
    int connectionTimeout();

    /**
     * Time after which an idle connection is closed.
     * Default: 600000ms (10 minutes)
     */
    @WithDefault("600000")
    int idleTimeout();

    /**
     * Maximum lifetime of a connection in the pool.
     * Default: 1800000ms (30 minutes)
     */
    @WithDefault("1800000")
    int maxLifetime();

    /**
     * Number of connections to be acquired at a time when pool needs more.
     * Default: 4
     */
    @WithDefault("4")
    int acquireIncrement();

    /**
     * Maximum number of statements to cache per connection.
     * Caching prepared statements improves performance for repeated queries.
     * Default: 256
     */
    @WithDefault("256")
    int preparedStatementCacheMaxSize();

    /**
     * Enable pipelining to send multiple queries on same connection.
     * Improves throughput for high TPS scenarios.
     * Default: true
     */
    @WithDefault("true")
    boolean pipeliningEnabled();

    /**
     * Maximum number of queries that can be pipelined.
     * Default: 256
     */
    @WithDefault("256")
    int pipeliningLimit();

    /**
     * Event loop size for async operations.
     * Should match vertx event-loops-pool-size.
     * Default: 4
     */
    @WithDefault("4")
    int eventLoopSize();

    /**
     * TCP keep alive to detect dead connections.
     * Default: true
     */
    @WithDefault("true")
    boolean tcpKeepAlive();

    /**
     * TCP no delay for reduced latency.
     * Default: true
     */
    @WithDefault("true")
    boolean tcpNoDelay();

    /**
     * Enable pool cleaner to remove idle connections.
     * Default: true
     */
    @WithDefault("true")
    boolean poolCleanerEnabled();

    /**
     * Interval between pool cleaner runs in milliseconds.
     * Default: 60000ms (1 minute)
     */
    @WithDefault("60000")
    int poolCleanerInterval();
}
