package com.csg.airtel.aaa4j.application.config;

import io.quarkus.reactive.datasource.ReactiveDataSource;
import io.quarkus.reactive.oracle.client.OraclePoolCreator;
import io.vertx.oracleclient.OracleConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Customizes the Oracle connection pool for optimal 1000 TPS handling.
 * Applies configuration from PoolConfig to tune pool behavior.
 */
@ApplicationScoped
public class OraclePoolCustomizer implements OraclePoolCreator {

    private static final Logger log = Logger.getLogger(OraclePoolCustomizer.class);

    private final PoolConfig poolConfig;

    @Inject
    public OraclePoolCustomizer(PoolConfig poolConfig) {
        this.poolConfig = poolConfig;
    }

    @Override
    @ReactiveDataSource("<default>")
    public Pools pools() {
        log.info("Configuring Oracle pool for 1000 TPS handling");

        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(poolConfig.maxSize())
                .setIdleTimeout(poolConfig.idleTimeout())
                .setIdleTimeoutUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
                .setMaxLifetime(poolConfig.maxLifetime())
                .setMaxLifetimeUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
                .setConnectionTimeout(poolConfig.connectionTimeout())
                .setConnectionTimeoutUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
                .setPoolCleanerPeriod(poolConfig.poolCleanerInterval())
                .setEventLoopSize(poolConfig.eventLoopSize())
                .setShared(true)
                .setName("oracle-pool-1000tps");

        log.infof("Oracle pool configured: maxSize=%d, connectionTimeout=%dms, idleTimeout=%dms, " +
                        "maxLifetime=%dms, eventLoopSize=%d, pipelining=%s",
                poolConfig.maxSize(),
                poolConfig.connectionTimeout(),
                poolConfig.idleTimeout(),
                poolConfig.maxLifetime(),
                poolConfig.eventLoopSize(),
                poolConfig.pipeliningEnabled());

        return new Pools() {
            @Override
            public PoolOptions poolOptions() {
                return poolOptions;
            }

            @Override
            public OracleConnectOptions connectOptions() {
                return null;
            }
        };
    }
}
