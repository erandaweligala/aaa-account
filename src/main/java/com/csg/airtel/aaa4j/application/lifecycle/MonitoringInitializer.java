package com.csg.airtel.aaa4j.application.lifecycle;

import com.csg.airtel.aaa4j.domain.service.MonitoringService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application lifecycle listener to initialize monitoring services on startup.
 */
@ApplicationScoped
public class MonitoringInitializer {

    private static final Logger logger = LoggerFactory.getLogger(MonitoringInitializer.class);

    @Inject
    MonitoringService monitoringService;

    /**
     * Initialize monitoring services when application starts.
     */
    void onStart(@Observes StartupEvent event) {
        logger.info("Initializing monitoring services...");
        monitoringService.initialize();
        logger.info("Monitoring services initialized successfully");
    }
}
