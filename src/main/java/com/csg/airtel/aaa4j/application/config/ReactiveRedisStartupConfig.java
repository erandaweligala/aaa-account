package com.csg.airtel.aaa4j.application.config;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Startup configuration that ensures ReactiveRedisDataSource is eagerly
 * initialized as a singleton bean when the application starts.
 *
 * This prevents lazy initialization delays on the first Redis operation
 * and validates the Redis connection during application startup.
 */
@ApplicationScoped
@Startup
public class ReactiveRedisStartupConfig {

    private static final Logger log = Logger.getLogger(ReactiveRedisStartupConfig.class);

    private final ReactiveRedisDataSource reactiveRedisDataSource;

    @Inject
    public ReactiveRedisStartupConfig(ReactiveRedisDataSource reactiveRedisDataSource) {
        this.reactiveRedisDataSource = reactiveRedisDataSource;
        log.info("ReactiveRedisDataSource singleton bean initialized at startup");
    }

    /**
     * Returns the ReactiveRedisDataSource instance.
     *
     * @return the ReactiveRedisDataSource instance
     */
    public ReactiveRedisDataSource getReactiveRedisDataSource() {
        return reactiveRedisDataSource;
    }
}
