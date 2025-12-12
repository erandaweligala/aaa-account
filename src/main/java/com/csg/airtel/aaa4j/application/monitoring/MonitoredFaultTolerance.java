package com.csg.airtel.aaa4j.application.monitoring;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enable fault tolerance monitoring on classes or methods.
 * When applied, the FaultToleranceMonitoringInterceptor will track
 * circuit breaker opens, retries, and errors.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MonitoredFaultTolerance {
}
