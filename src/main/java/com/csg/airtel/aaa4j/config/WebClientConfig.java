package com.csg.airtel.aaa4j.config;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Configuration for Vert.x WebClient
 * Provides a configured WebClient bean for making HTTP requests
 */
@ApplicationScoped
public class WebClientConfig {

    @ConfigProperty(name = "coa.nas.url", defaultValue = "http://localhost:3799")
    String coaNasUrl;

    @ConfigProperty(name = "coa.connect.timeout", defaultValue = "2000")
    int connectTimeout;

    @ConfigProperty(name = "coa.read.timeout", defaultValue = "3000")
    int readTimeout;

    /**
     * Produces a configured WebClient bean for CoA HTTP requests
     * Features:
     * - Connection timeout: 2 seconds
     * - Read timeout: 3 seconds
     * - Keep-alive enabled
     * - Pipelining enabled for performance
     */
    @Produces
    @Named("coaWebClient")
    @ApplicationScoped
    public WebClient createWebClient(Vertx vertx) {
        WebClientOptions options = new WebClientOptions()
                .setConnectTimeout(connectTimeout)
                .setIdleTimeout(readTimeout)
                .setKeepAlive(true)
                .setPipelining(true)
                .setTryUseCompression(true)
                .setFollowRedirects(false)
                .setMaxPoolSize(50);

        return WebClient.create(vertx, options);
    }
}
