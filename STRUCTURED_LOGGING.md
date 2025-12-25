# Structured Logging Guide for Operations Team

## Overview

This AAA Accounting Service now uses **structured JSON logging** for easy monitoring, searching, and troubleshooting in high-TPS (2500+ TPS) production environments.

## Log Format

### File Logs (Production)
- **Location**: `/var/logs/aaa-account.log`
- **Format**: JSON (one log entry per line)
- **Rotation**: 100MB per file, 10 backups
- **Async**: Non-blocking async logging for high performance

### Console Logs (Development)
- **Format**: Human-readable text format
- **Use**: Local development and debugging

## Log Structure

Every JSON log entry contains:

```json
{
  "timestamp": "2025-01-15 10:23:45,123",
  "level": "INFO",
  "logger": "com.csg.airtel.aaa4j.domain.service.StartHandler",
  "thread": "executor-thread-1",
  "message": "Processing accounting START request",
  "app.name": "aaa-accounting-service",
  "app.version": "1.0.0",
  "environment": "production",
  "requestId": "abc-123-def-456",
  "userId": "user@example.com",
  "sessionId": "sess-789",
  "operation": "START",
  "duration_ms": 45,
  "status": "success"
}
```

## Key Fields for Operations

### Correlation Fields (MDC)
- **requestId**: Trace ID for following a request across all services
- **userId**: Username for the accounting request
- **sessionId**: Session ID for tracking session lifecycle
- **operation**: Type of operation (START, INTERIM, STOP, COA, KAFKA_CONSUME)

### Performance Fields
- **duration_ms**: Execution time in milliseconds
- **status**: Operation status (success, failed, rejected, duplicate)

### Error Fields
- **error_code**: Specific error code for quick identification
  - `CIRCUIT_BREAKER_OPEN`: Redis circuit breaker triggered
  - `BALANCE_EXHAUSTED`: User has no data balance
  - `CONCURRENCY_LIMIT_EXCEEDED`: Too many concurrent sessions
  - `KAFKA_SEND_FAILED`: Failed to send Kafka message
  - `REDIS_GET_FAILED`: Failed to retrieve from cache
  - etc.
- **error_type**: Java exception class name
- **error**: Error message details

### Component Fields
- **component**: System component (kafka-consumer, kafka-producer, redis-cache)
- **topic**: Kafka topic name
- **partition**: Kafka partition number
- **offset**: Kafka offset
- **cacheHit**: Whether Redis cache hit (true/false)

## Common Use Cases

### 1. Track a Specific Request
Search for logs with the same `requestId`:
```
requestId="abc-123-def-456"
```

### 2. Find All Errors for a User
```
userId="user@example.com" AND level="ERROR"
```

### 3. Monitor Session Lifecycle
```
sessionId="sess-789" AND operation IN ["START", "INTERIM", "STOP"]
```

### 4. Find Slow Operations (>100ms)
```
duration_ms > 100
```

### 5. Monitor Circuit Breaker Issues
```
error_code="CIRCUIT_BREAKER_OPEN"
```

### 6. Track Kafka Processing Issues
```
component="kafka-consumer" AND status="failed"
```

### 7. Monitor Cache Performance
```
component="redis-cache" AND cacheHit=false
```

### 8. Find Balance Exhaustion Events
```
error_code="BALANCE_EXHAUSTED"
```

## Log Levels

- **DEBUG**: Detailed diagnostic info (disabled in production by default)
  - Cache hits/misses
  - User data retrievals
  - Kafka partition/offset details

- **INFO**: Normal operational messages
  - Request start/completion
  - Successful operations
  - Session lifecycle events

- **WARN**: Warning conditions
  - Duplicate requests
  - Recoverable errors

- **ERROR**: Error conditions
  - Failed operations
  - Circuit breaker triggers
  - Kafka send failures
  - Database/cache errors

## Operations by Type

### START Operations
```json
{
  "operation": "START",
  "username": "user@example.com",
  "sessionId": "sess-123",
  "nasIP": "10.0.0.1",
  "framedIP": "192.168.1.100",
  "duration_ms": 45,
  "status": "success"
}
```

### INTERIM Operations
```json
{
  "operation": "INTERIM",
  "username": "user@example.com",
  "sessionId": "sess-123",
  "acctInputOctets": 1024000,
  "acctOutputOctets": 2048000,
  "duration_ms": 32,
  "status": "success"
}
```

### STOP Operations
```json
{
  "operation": "STOP",
  "username": "user@example.com",
  "sessionId": "sess-123",
  "bucketId": "bucket-456",
  "acctSessionTime": 3600,
  "duration_ms": 38,
  "status": "success"
}
```

### Kafka Events
```json
{
  "component": "kafka-consumer",
  "topic": "accounting",
  "partition": 3,
  "offset": 12345,
  "acctStatusType": "START",
  "duration_ms": 125,
  "status": "success"
}
```

### Cache Operations
```json
{
  "component": "redis-cache",
  "operation": "GET",
  "userId": "user@example.com",
  "cacheHit": true,
  "sessionCount": 2,
  "duration_ms": 5,
  "status": "success"
}
```

## Integration with Log Aggregators

### Fluent Bit / Elasticsearch / Splunk
The JSON format is automatically parseable. Key fields to index:
- `requestId`, `userId`, `sessionId`
- `operation`, `status`, `error_code`
- `duration_ms`
- `timestamp`

### Prometheus/Grafana
Create alerts based on:
- High error rates: `level="ERROR"`
- Slow operations: `duration_ms > threshold`
- Circuit breaker triggers: `error_code="CIRCUIT_BREAKER_OPEN"`
- Failed Kafka operations: `component="kafka-producer" AND status="failed"`

## Performance Considerations

- **Async Logging**: All logs are written asynchronously to minimize impact on request processing
- **Conditional Debug**: Debug logs are guarded with `isDebugEnabled()` checks
- **Queue Size**: 2048 log entries can be buffered
- **Overflow**: BLOCK strategy ensures no log loss under high load

## Troubleshooting

### High Error Rates
1. Search for: `level="ERROR"` and group by `error_code`
2. Check for circuit breaker issues: `error_code="CIRCUIT_BREAKER_OPEN"`
3. Monitor external dependencies (Redis, Kafka, Oracle)

### Performance Issues
1. Find slow operations: `duration_ms > 100`
2. Check cache hit rates: `component="redis-cache"` and aggregate `cacheHit`
3. Monitor Kafka lag using partition/offset fields

### Session Issues
1. Track session lifecycle: Filter by `sessionId` and sort by timestamp
2. Check balance exhaustion: `error_code="BALANCE_EXHAUSTED"`
3. Find concurrency issues: `error_code="CONCURRENCY_LIMIT_EXCEEDED"`

## Configuration

Logging configuration is in `src/main/resources/application.yml`:

```yaml
quarkus:
  log:
    file:
      json:
        ~: true                    # Enable JSON logging
        pretty-print: false        # Compact format for performance
        additional-field:
          app.name: "aaa-accounting-service"
          app.version: "1.0.0"
          environment: "production"
```

To adjust log levels, modify:
```yaml
quarkus:
  log:
    category:
      "com.csg.airtel.aaa4j":
        level: DEBUG  # Change to INFO for production
```
