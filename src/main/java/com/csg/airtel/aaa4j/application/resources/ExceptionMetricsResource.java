package com.csg.airtel.aaa4j.application.resources;

import com.csg.airtel.aaa4j.domain.service.ExceptionMetricsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only view of the aggregated exception metrics surfaced by {@link ExceptionMetricsService}.
 * Useful for dashboards and ad-hoc inspection without scraping the full Prometheus endpoint.
 */
@Path("/monitoring/exceptions")
@ApplicationScoped
public class ExceptionMetricsResource {

    private final ExceptionMetricsService exceptionMetricsService;

    public ExceptionMetricsResource(ExceptionMetricsService exceptionMetricsService) {
        this.exceptionMetricsService = exceptionMetricsService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> summary() {
        Map<String, ExceptionMetricsService.ExceptionStats> snapshot = exceptionMetricsService.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", exceptionMetricsService.getTotalRootCount());
        body.put("byType", snapshot);
        return body;
    }
}
