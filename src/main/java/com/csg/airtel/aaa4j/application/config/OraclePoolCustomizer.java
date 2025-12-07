package com.csg.airtel.aaa4j.application.config;

import io.quarkus.reactive.oracle.client.OraclePoolCreator;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.oracleclient.OraclePool;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.oracleclient.OracleConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Customizes the Oracle connection pool for optimal 1000 TPS handling.
 * Applies configuration from PoolConfig to tune pool behavior.
 */
//todo 'create(Input)' in 'com.csg.airtel.aaa4j.application.config.OraclePoolCustomizer' clashes with 'create(Input)' in 'io.quarkus.reactive.oracle.client.OraclePoolCreator'; incompatible return type
@Singleton
public class OraclePoolCustomizer implements OraclePoolCreator {

    private static final Logger log = Logger.getLogger(OraclePoolCustomizer.class);

    private final PoolConfig poolConfig;

    @Inject
    public OraclePoolCustomizer(PoolConfig poolConfig) {
        this.poolConfig = poolConfig;
    }

    @Override
    public Pool create(Input input) {
        log.info("Configuring Oracle pool for 1000 TPS handling");

        // Get Quarkus-configured connect options as base
        OracleConnectOptions connectOptions = input.oracleConnectOptions();

        // Apply custom pool configuration for high TPS handling
        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(poolConfig.maxSize())
                .setIdleTimeout(poolConfig.idleTimeout())
                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
                .setMaxLifetime(poolConfig.maxLifetime())
                .setMaxLifetimeUnit(TimeUnit.MILLISECONDS)
                .setConnectionTimeout(poolConfig.connectionTimeout())
                .setConnectionTimeoutUnit(TimeUnit.MILLISECONDS)
                .setPoolCleanerPeriod(poolConfig.poolCleanerInterval())
                .setEventLoopSize(poolConfig.eventLoopSize())
                .setShared(true)
                .setName("oracle-pool-1000tps");

        // Apply TCP settings to connect options for better performance
        connectOptions
                .setTcpKeepAlive(poolConfig.tcpKeepAlive())
                .setTcpNoDelay(poolConfig.tcpNoDelay());

        // Apply prepared statement cache size
        connectOptions.setPreparedStatementCacheMaxSize(poolConfig.preparedStatementCacheMaxSize());

        log.infof("Oracle pool configured: maxSize=%d, connectionTimeout=%dms, idleTimeout=%dms, " +
                        "maxLifetime=%dms, eventLoopSize=%d, pipelining=%s, tcpKeepAlive=%s, tcpNoDelay=%s",
                poolConfig.maxSize(),
                poolConfig.connectionTimeout(),
                poolConfig.idleTimeout(),
                poolConfig.maxLifetime(),
                poolConfig.eventLoopSize(),
                poolConfig.pipeliningEnabled(),
                poolConfig.tcpKeepAlive(),
                poolConfig.tcpNoDelay());

        // Wrap bare Vert.x in Mutiny wrapper to create Mutiny Pool
        Vertx mutinyVertx = Vertx.newInstance(input.vertx());
        return OraclePool.pool(mutinyVertx, connectOptions, poolOptions);
    }
}
