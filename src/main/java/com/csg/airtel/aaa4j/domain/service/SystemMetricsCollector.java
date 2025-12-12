package com.csg.airtel.aaa4j.domain.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;

/**
 * Collects system-level metrics including CPU, memory, threads, and GC statistics.
 * Uses Micrometer binders for JVM metrics and custom collectors for OS-level metrics.
 */
@ApplicationScoped
public class SystemMetricsCollector {

    private static final Logger log = Logger.getLogger(SystemMetricsCollector.class);

    @Inject
    MeterRegistry registry;

    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final Runtime runtime = Runtime.getRuntime();

    public void init() {
        log.info("Initializing SystemMetricsCollector");

        // Register JVM metrics binders (provided by Micrometer)
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        // Register custom system metrics
        registerCpuMetrics();
        registerMemoryMetrics();
        registerThreadMetrics();

        log.info("SystemMetricsCollector initialized successfully");
    }

    private void registerCpuMetrics() {
        // System CPU load (0.0 to 1.0)
        Gauge.builder("system.cpu.usage", osBean, bean -> {
                    try {
                        // Try to get system CPU load using reflection (available in com.sun.management.OperatingSystemMXBean)
                        java.lang.reflect.Method method = bean.getClass().getMethod("getSystemCpuLoad");
                        method.setAccessible(true);
                        Object value = method.invoke(bean);
                        return value != null ? ((Number) value).doubleValue() : -1.0;
                    } catch (Exception e) {
                        return -1.0;
                    }
                })
                .description("System CPU usage (0.0 to 1.0)")
                .register(registry);

        // Process CPU load (0.0 to 1.0)
        Gauge.builder("process.cpu.usage", osBean, bean -> {
                    try {
                        java.lang.reflect.Method method = bean.getClass().getMethod("getProcessCpuLoad");
                        method.setAccessible(true);
                        Object value = method.invoke(bean);
                        return value != null ? ((Number) value).doubleValue() : -1.0;
                    } catch (Exception e) {
                        return -1.0;
                    }
                })
                .description("Process CPU usage (0.0 to 1.0)")
                .register(registry);

        // System load average
        Gauge.builder("system.load.average.1m", osBean, OperatingSystemMXBean::getSystemLoadAverage)
                .description("System load average for the last minute")
                .register(registry);

        // Available processors
        Gauge.builder("system.cpu.count", osBean, OperatingSystemMXBean::getAvailableProcessors)
                .description("Number of available processors")
                .register(registry);
    }

    private void registerMemoryMetrics() {
        // JVM Memory metrics
        Gauge.builder("jvm.memory.used", runtime, r -> r.totalMemory() - r.freeMemory())
                .description("Used JVM memory in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("jvm.memory.free", runtime, Runtime::freeMemory)
                .description("Free JVM memory in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("jvm.memory.total", runtime, Runtime::totalMemory)
                .description("Total JVM memory in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("jvm.memory.max", runtime, Runtime::maxMemory)
                .description("Max JVM memory in bytes")
                .baseUnit("bytes")
                .register(registry);

        // Memory usage percentage
        Gauge.builder("jvm.memory.usage.percent", runtime,
                        r -> ((double) (r.totalMemory() - r.freeMemory()) / r.maxMemory()) * 100)
                .description("JVM memory usage percentage")
                .baseUnit("percent")
                .register(registry);

        // Physical memory (if available)
        Gauge.builder("system.memory.total", osBean, bean -> {
                    try {
                        java.lang.reflect.Method method = bean.getClass().getMethod("getTotalPhysicalMemorySize");
                        method.setAccessible(true);
                        Object value = method.invoke(bean);
                        return value != null ? ((Number) value).longValue() : -1L;
                    } catch (Exception e) {
                        return -1L;
                    }
                })
                .description("Total physical memory in bytes")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("system.memory.free", osBean, bean -> {
                    try {
                        java.lang.reflect.Method method = bean.getClass().getMethod("getFreePhysicalMemorySize");
                        method.setAccessible(true);
                        Object value = method.invoke(bean);
                        return value != null ? ((Number) value).longValue() : -1L;
                    } catch (Exception e) {
                        return -1L;
                    }
                })
                .description("Free physical memory in bytes")
                .baseUnit("bytes")
                .register(registry);
    }

    private void registerThreadMetrics() {
        // Thread count
        Gauge.builder("jvm.threads.count", threadBean, ThreadMXBean::getThreadCount)
                .description("Current number of live threads")
                .register(registry);

        // Daemon thread count
        Gauge.builder("jvm.threads.daemon", threadBean, ThreadMXBean::getDaemonThreadCount)
                .description("Current number of live daemon threads")
                .register(registry);

        // Peak thread count
        Gauge.builder("jvm.threads.peak", threadBean, ThreadMXBean::getPeakThreadCount)
                .description("Peak live thread count since JVM start")
                .register(registry);

        // Total started thread count
        Gauge.builder("jvm.threads.started", threadBean, ThreadMXBean::getTotalStartedThreadCount)
                .description("Total number of threads created and started since JVM start")
                .register(registry);

        // Deadlocked threads
        Gauge.builder("jvm.threads.deadlocked", threadBean, bean -> {
                    long[] deadlockedThreads = bean.findDeadlockedThreads();
                    return deadlockedThreads != null ? deadlockedThreads.length : 0;
                })
                .description("Number of deadlocked threads")
                .register(registry);
    }

    /**
     * Get current CPU usage as a percentage (0-100)
     */
    public double getCurrentCpuUsage() {
        try {
            java.lang.reflect.Method method = osBean.getClass().getMethod("getProcessCpuLoad");
            method.setAccessible(true);
            Object value = method.invoke(osBean);
            return value != null ? ((Number) value).doubleValue() * 100 : -1.0;
        } catch (Exception e) {
            return -1.0;
        }
    }

    /**
     * Get current memory usage as a percentage (0-100)
     */
    public double getCurrentMemoryUsage() {
        return ((double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory()) * 100;
    }

    /**
     * Get current thread count
     */
    public int getCurrentThreadCount() {
        return threadBean.getThreadCount();
    }

    /**
     * Check if there are any deadlocked threads
     */
    public boolean hasDeadlockedThreads() {
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        return deadlockedThreads != null && deadlockedThreads.length > 0;
    }
}
