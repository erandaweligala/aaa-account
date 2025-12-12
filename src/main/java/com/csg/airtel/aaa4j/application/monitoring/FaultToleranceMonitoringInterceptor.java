package com.csg.airtel.aaa4j.application.monitoring;

import com.csg.airtel.aaa4j.domain.service.MonitoringService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;

/**
 * Interceptor that monitors MicroProfile Fault Tolerance annotations.
 * Automatically tracks circuit breaker opens, retries, and fallback executions.
 */
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 100)
@MonitoredFaultTolerance
public class FaultToleranceMonitoringInterceptor {

    private static final Logger log = Logger.getLogger(FaultToleranceMonitoringInterceptor.class);

    @Inject
    MonitoringService monitoringService;

    @AroundInvoke
    public Object monitorFaultTolerance(InvocationContext context) throws Exception {
        Method method = context.getMethod();
        String methodName = method.getDeclaringClass().getSimpleName() + "." + method.getName();

        boolean hasCircuitBreaker = method.isAnnotationPresent(CircuitBreaker.class);
        boolean hasRetry = method.isAnnotationPresent(Retry.class);
        boolean hasFallback = method.isAnnotationPresent(Fallback.class);

        try {
            Object result = context.proceed();
            return result;
        } catch (Exception e) {
            // Track the error based on the component
            trackComponentError(methodName, e);

            // If method has retry, track it
            if (hasRetry) {
                if (isKafkaOperation(methodName)) {
                    monitoringService.recordKafkaRetry();
                }
            }

            // If method has circuit breaker and it's opening
            if (hasCircuitBreaker) {
                if (isKafkaOperation(methodName)) {
                    monitoringService.recordKafkaCircuitBreakerOpen();
                }
            }

            throw e;
        }
    }

    private void trackComponentError(String methodName, Exception e) {
        if (isKafkaOperation(methodName)) {
            monitoringService.recordKafkaError("operation", e);
        } else if (isRedisOperation(methodName)) {
            monitoringService.recordRedisError("operation", e);
        } else if (isDatabaseOperation(methodName)) {
            monitoringService.recordDatabaseError("operation", e);
        }
    }

    private boolean isKafkaOperation(String methodName) {
        return methodName.contains("Producer") ||
               methodName.contains("produce") ||
               methodName.contains("Kafka");
    }

    private boolean isRedisOperation(String methodName) {
        return methodName.contains("Cache") ||
               methodName.contains("Redis") ||
               methodName.contains("cache");
    }

    private boolean isDatabaseOperation(String methodName) {
        return methodName.contains("Repository") ||
               methodName.contains("Database") ||
               methodName.contains("Bucket");
    }
}
