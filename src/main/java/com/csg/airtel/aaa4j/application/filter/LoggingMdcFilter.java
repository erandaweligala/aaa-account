package com.csg.airtel.aaa4j.application.filter;

import com.csg.airtel.aaa4j.application.common.LoggingUtil;
import com.csg.airtel.aaa4j.application.common.TraceIdGenerator;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.MDC;

@Provider
public class LoggingMdcFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        MDC.put(LoggingUtil.TRACE_ID, TraceIdGenerator.generateTraceId());

        String userName = requestContext.getUriInfo().getPathParameters().getFirst("userName");
        String sessionId = requestContext.getUriInfo().getPathParameters().getFirst("sessionId");

        if (userName != null) {
            MDC.put(LoggingUtil.USER_NAME, userName);
        }
        if (sessionId != null) {
            MDC.put(LoggingUtil.SESSION_ID, sessionId);
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        MDC.remove(LoggingUtil.TRACE_ID);
        MDC.remove(LoggingUtil.USER_NAME);
        MDC.remove(LoggingUtil.SESSION_ID);
    }
}
