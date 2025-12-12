# AAA Accounting Service - Monitoring & Observability

## Overview

This document describes the comprehensive monitoring and observability solution implemented for the AAA Accounting Service.

## Features Implemented

### 1. Application Errors Monitoring
- **Intercommunication Errors**: Tracks errors between internal components
- **Request Errors**: Monitors errors during request processing (START, INTERIM, STOP, COA)
- **Component-Specific Errors**: Categorized by source and type

### 2. Request Count Metrics
- **Authentication Requests**: Counter for all authentication operations
- **Accounting Requests**: Separate counters for:
  - START requests
  - INTERIM requests
  - STOP requests
  - COA (Change of Authorization) requests

### 3. Component Failure Tracking
- **Authentication Failures**: Tracks auth component failures with reason codes
- **Accounting Failures**: Monitors accounting operation failures
- **COA Failures**: Tracks COA disconnect and policy update failures
- **Session Creation Failures**: Monitors reasons like:
  - Concurrency limit exceeded
  - Balance exhausted
  - No service buckets
  - Zero quota

### 4. Open Sessions Monitoring
- **Active Sessions Gauge**: Real-time count of active sessions
- **Total Sessions Created**: Cumulative counter since startup
- **Total Sessions Terminated**: Cumulative termination counter
- **Idle Sessions Terminated**: Sessions terminated due to timeout

### 5. Connectivity Loss Detection
- **Database Connectivity**: Binary gauge (1=connected, 0=disconnected)
- **Redis Connectivity**: Real-time cache connectivity status
- **Kafka Connectivity**: Message broker connection status
- **BNG Connectivity**: Broadband Network Gateway connectivity

### 6. Application Status (CPU, Memory, Threads)
- **System CPU Usage**: Overall system CPU utilization (0-100%)
- **Process CPU Usage**: Application-specific CPU usage
- **JVM Memory Metrics**:
  - Used memory
  - Free memory
  - Total memory
  - Max memory
  - Usage percentage
- **Thread Metrics**:
  - Active thread count
  - Daemon thread count
  - Peak thread count
  - Deadlocked threads detection

### 7. Core Component Errors
- **Kafka Errors**:
  - Production errors
  - Consumption errors
  - Circuit breaker opens
  - Retry attempts
- **Database Errors**: Query failures, connection issues
- **Redis Errors**: Cache operation failures
- **Integration API Errors**: Third-party API call failures

### 8. Performance Timers
- **Handler Execution Times**: START, INTERIM, STOP, COA handlers
- **Operation Timers**:
  - Database queries
  - Kafka message production
  - Redis operations
  - Third-party API calls

## Architecture

### Components

1. **MonitoringService** (`domain/service/MonitoringService.java`)
   - Centralized metrics collection
   - Micrometer-based counters, gauges, and timers
   - Error categorization and tracking

2. **SystemMetricsCollector** (`domain/service/SystemMetricsCollector.java`)
   - JVM metrics (memory, GC, threads)
   - System metrics (CPU, load average)
   - Uses Micrometer binders for standard JVM metrics

3. **MetricsExporter** (`domain/service/MetricsExporter.java`)
   - Exports metrics to JSON file every 30 seconds
   - Designed for Fluent Bit consumption
   - Atomic file writes to prevent corruption

4. **Health Checks** (`application/health/*`)
   - `DatabaseHealthCheck`: Verifies Oracle DB connectivity
   - `RedisHealthCheck`: Validates Redis cache accessibility
   - `KafkaHealthCheck`: Monitors Kafka broker connection
   - `ApplicationHealthCheck`: Overall service health status

5. **MonitoringInitializer** (`application/config/MonitoringInitializer.java`)
   - Bootstraps all monitoring components on startup
   - Initializes metrics, collectors, and exporters

## Endpoints

### Metrics
- **Prometheus**: `http://localhost:9905/q/metrics`
  - Scrape interval: 30s recommended
  - Format: Prometheus exposition format

### Health Checks
- **Overall Health**: `http://localhost:9905/q/health`
- **Liveness Probe**: `http://localhost:9905/q/health/live` (for Kubernetes)
- **Readiness Probe**: `http://localhost:9905/q/health/ready` (for Kubernetes)
- **Health UI**: `http://localhost:9905/q/health-ui` (web interface)

### Metrics File Export
- **Location**: `/var/log/aaa-account/metrics.json`
- **Update Frequency**: Every 30 seconds
- **Format**: JSON
- **Consumer**: Fluent Bit (or any log aggregator)

## Configuration

### Enable Monitoring

Add to `application.yml`:

```yaml
monitoring:
  metrics-export:
    enabled: true
    file-path: /var/log/aaa-account/metrics.json
    interval: 30s
```

### Configure Prometheus

```yaml
quarkus:
  micrometer:
    enabled: true
    export:
      prometheus:
        enabled: true
```

### Configure Health Checks

```yaml
quarkus:
  smallrye-health:
    root-path: /q/health
    liveness-path: /q/health/live
    readiness-path: /q/health/ready
```

## Fluent Bit Integration

### Setup

1. Install Fluent Bit:
   ```bash
   curl https://raw.githubusercontent.com/fluent/fluent-bit/master/install.sh | sh
   ```

2. Copy the provided `fluent-bit.conf` to `/etc/fluent-bit/`

3. Configure output (Elasticsearch, Datadog, Splunk, CloudWatch, etc.)

4. Start Fluent Bit:
   ```bash
   systemctl start fluent-bit
   systemctl enable fluent-bit
   ```

### Supported Outputs

The provided configuration supports:
- **Elasticsearch**: For log search and visualization
- **Datadog**: For unified monitoring and APM
- **Splunk**: Enterprise logging platform
- **AWS CloudWatch**: Cloud-native AWS monitoring
- **File**: Local backup

## Metrics Reference

### Counters

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `accounting.requests` | `type=START/INTERIM/STOP/COA` | Request counts by type |
| `application.errors` | `category=intercommunication/request/database/kafka/redis/bng/api` | Error counts by category |
| `component.failures` | `component=authentication/accounting/coa/session_creation/balance_update` | Component failure counts |
| `kafka.errors` | `operation=produce/consume` | Kafka operation errors |
| `kafka.circuit_breaker` | `state=open` | Circuit breaker open events |
| `kafka.retries` | - | Kafka retry attempts |
| `api.calls` | `api=<name>` | Third-party API call counts |
| `api.errors` | `api=<name>` | API-specific error counts |
| `error.details` | `category=<cat>, type=<type>` | Detailed error categorization |

### Gauges

| Metric Name | Description |
|-------------|-------------|
| `sessions.active` | Current active sessions |
| `sessions.created.total` | Total sessions created |
| `sessions.terminated.total` | Total sessions terminated |
| `sessions.idle_terminated` | Idle timeout terminations |
| `connectivity.database` | Database connection (1/0) |
| `connectivity.redis` | Redis connection (1/0) |
| `connectivity.kafka` | Kafka connection (1/0) |
| `connectivity.bng` | BNG connection (1/0) |
| `system.cpu.usage` | System CPU usage (0-1) |
| `process.cpu.usage` | Process CPU usage (0-1) |
| `jvm.memory.usage.percent` | JVM memory usage (0-100) |
| `jvm.threads.count` | Active thread count |
| `jvm.threads.deadlocked` | Deadlocked thread count |

### Timers

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `handler.duration` | `handler=START/INTERIM/STOP/COA` | Handler execution time |
| `operation.duration` | `operation=database_query/kafka_produce/redis` | Operation execution time |
| `api.duration` | `api=<name>` | API call duration |

## Usage Examples

### Programmatic Metrics Recording

```java
@Inject
MonitoringService monitoringService;

// Record a START request
monitoringService.recordStartRequest();

// Time an operation
Timer.Sample sample = monitoringService.startTimer();
// ... perform operation ...
monitoringService.recordStartHandlerTime(sample);

// Track a session creation
monitoringService.recordSessionCreated();

// Record an error
monitoringService.recordDatabaseError("query", exception);

// Track connectivity
monitoringService.setDatabaseConnectivity(false);
```

### Querying Prometheus Metrics

```promql
# Error rate per minute
rate(application_errors_total[1m])

# Active sessions
sessions_active

# 95th percentile handler duration
histogram_quantile(0.95, handler_duration_seconds_bucket{handler="START"})

# CPU usage
process_cpu_usage * 100

# Database connectivity
connectivity_database
```

### Sample Grafana Dashboard Queries

```promql
# Panel: Request Rate
sum(rate(accounting_requests_total[5m])) by (type)

# Panel: Error Rate
sum(rate(application_errors_total[5m])) by (category)

# Panel: Session Count
sessions_active

# Panel: P99 Latency
histogram_quantile(0.99, sum(rate(handler_duration_seconds_bucket[5m])) by (le, handler))

# Panel: Connectivity Status
sum(connectivity_database + connectivity_redis + connectivity_kafka + connectivity_bng)
```

## Alerting Examples

### Prometheus Alert Rules

```yaml
groups:
  - name: aaa_accounting_alerts
    interval: 30s
    rules:
      - alert: HighErrorRate
        expr: rate(application_errors_total[5m]) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High error rate detected"
          description: "Error rate is {{ $value }} errors/sec"

      - alert: DatabaseDown
        expr: connectivity_database == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Database connectivity lost"

      - alert: HighCPUUsage
        expr: process_cpu_usage > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High CPU usage: {{ $value | humanizePercentage }}"

      - alert: HighMemoryUsage
        expr: jvm_memory_usage_percent > 90
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "JVM memory usage critical"

      - alert: KafkaCircuitBreakerOpen
        expr: increase(kafka_circuit_breaker_total[5m]) > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Kafka circuit breaker opened"
```

## Troubleshooting

### Metrics Not Appearing

1. Check MonitoringService initialization:
   ```bash
   grep "MonitoringService initialized" /var/log/aaa-account/aaa-account.log
   ```

2. Verify metrics endpoint:
   ```bash
   curl http://localhost:9905/q/metrics | grep accounting
   ```

3. Check metrics export file:
   ```bash
   cat /var/log/aaa-account/metrics.json | jq
   ```

### High Memory Usage

Monitor JVM metrics:
```bash
curl -s http://localhost:9905/q/metrics | grep jvm_memory
```

### Connectivity Issues

Check health endpoint:
```bash
curl http://localhost:9905/q/health | jq
```

## Performance Impact

The monitoring implementation is designed for minimal overhead:
- **Metrics Collection**: < 1% CPU overhead
- **Memory**: ~50 MB additional heap usage
- **Logging Guards**: Conditional logging prevents overhead
- **Async Export**: Metrics export runs asynchronously

## MIB/SNMP Support

While SNMP MIB support is not directly implemented, metrics can be exposed to SNMP using:

1. **Prometheus SNMP Exporter**: Convert Prometheus metrics to SNMP
2. **Custom SNMP Agent**: Use JMX bridge to expose metrics as MIB

Example SNMP exporter configuration:
```yaml
modules:
  aaa_accounting:
    walk:
      - 1.3.6.1.4.1.2021  # System OID base
    metrics:
      - name: sessionCount
        oid: 1.3.6.1.4.1.2021.1.1
        type: gauge
        help: Active session count
```

## Best Practices

1. **Scrape Interval**: Set Prometheus scrape interval to 30s
2. **Retention**: Keep detailed metrics for 15 days, aggregated for 90 days
3. **Alerting**: Set up alerts for critical metrics (connectivity, errors, CPU)
4. **Dashboard**: Create Grafana dashboards for operational visibility
5. **Log Aggregation**: Use Fluent Bit to forward metrics to centralized logging
6. **Capacity Planning**: Monitor trends for CPU, memory, and session counts

## Future Enhancements

- [ ] Distributed tracing with OpenTelemetry
- [ ] Custom business metrics (revenue, usage patterns)
- [ ] ML-based anomaly detection
- [ ] Auto-scaling triggers based on metrics
- [ ] SLA monitoring and reporting
