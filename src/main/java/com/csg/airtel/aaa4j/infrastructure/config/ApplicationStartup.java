package com.csg.airtel.aaa4j.infrastructure.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@ApplicationScoped
public class ApplicationStartup {
    private static final Logger log = Logger.getLogger(ApplicationStartup.class);

    void onStart(@Observes StartupEvent event) {
        createLogDirectory();
    }

    private void createLogDirectory() {
        try {
            Path logDir = Paths.get("logs");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
                log.info("Created logs directory: " + logDir.toAbsolutePath());
            } else {
                log.info("Logs directory already exists: " + logDir.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("Failed to create logs directory", e);
        }
    }
}
