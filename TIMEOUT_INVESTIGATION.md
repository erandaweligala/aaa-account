# Timeout Investigation — 1200 TPS / 5 Pods / 1 Core

## Timeouts Observed

| Timeout Source | Method | Configured Timeout | Frequency |
|---|---|---|---|
| `CacheClient#getUserData` | Redis GET | **5s** | Most frequent |
| `CacheClient#storeUserData` | Redis SET (+ group check) | **8s** | Moderate |
| `AccountProducer#produceAccountingCDREvent` | Kafka produce | **10s** | Less frequent |

Per-pod load: **~240 TPS per pod** (1200 / 5).

## Root Cause: Thread/connection pools over-provisioned for 1-core pods

The `application.yml` is tuned for "2500 TPS" multi-core nodes, but actual deployment is **1 core per pod**. This creates severe CPU contention.

### 1. Thread pools far exceed CPU capacity

| Config | Current Value | Recommended for 1 core |
|---|---|---|
| `vertx.event-loops-pool-size` | **32** | **2** (2x cores) |
| `vertx.worker-pool-size` | **64** | **10-20** |
| `vertx.internal-blocking-pool-size` | **100** | **10-20** |
| `thread-pool.core-threads` | **100** | **10-20** |
| `thread-pool.max-threads` | **500** | **50** |
| `pool.event-loop-size` | **32** | **2** |

With 1 core, 700+ threads cause **excessive context switching**. The kernel spends more time swapping threads than doing useful work. This delays Redis response processing, Kafka ACK handling, and MicroProfile Fault Tolerance timeout monitoring.

### 2. Redis connection pool oversized

| Config | Current Value | Recommended for 1 core |
|---|---|---|
| `redis.max-pool-size` | **600** | **50-100** |
| `redis.max-waiting-handlers` | **12000** | **1000** |

600 Redis connections on 1 core means connection lifecycle management consumes CPU that should process requests.

### 3. No `@Bulkhead` — unbounded concurrency

None of the fault-tolerant methods use `@Bulkhead`. At 240 TPS per pod, if `getUserData` takes 500ms under CPU starvation, ~120 concurrent Redis calls pile up — all competing for 1 core.

**Affected methods:**
- `CacheClient#getUserData` (CacheClient.java:206)
- `CacheClient#storeUserData` (CacheClient.java:140)
- `AccountProducer#produceAccountingCDREvent` (AccountProducer.java:140)

### 4. `storeUserData` sequential Redis calls under load

`CacheClient.java:140-194` — `storeUserData` performs:
1. `valueCommands.get(groupKey)` — check if group cache exists
2. Then `valueCommands.set(groupKey, ...)` + `valueCommands.set(key, ...)` in parallel

On a 1-core pod under load, each round-trip is delayed by thread scheduling. Chained operations breach the 8s timeout.

### 5. Kafka producer contention

- `acks: all` — requires all ISR replicas to acknowledge
- `buffer.memory: 67108864` (64MB) — large buffer for a 1-core pod
- Kafka's internal I/O thread competes with the single core

### 6. Consumer ACKs before processing completes

`AccountingConsumer.java:49` — messages ACK'd immediately, processed async on worker pool. Consumer pulls messages faster than 1 core can process, creating backlog.

## Recommendations

### Thread pool right-sizing
```yaml
vertx:
  event-loops-pool-size: 2        # 2x cores
  worker-pool-size: 20
  internal-blocking-pool-size: 20

thread-pool:
  core-threads: 20
  max-threads: 50
  queue-size: 2000
```

### Redis pool right-sizing
```yaml
redis:
  max-pool-size: 80
  max-waiting-handlers: 1000
  max-pool-waiting: 500
```

### Add @Bulkhead annotations
```java
@Bulkhead(value = 30)
@Timeout(value = 5, unit = ChronoUnit.SECONDS)
public Uni<UserSessionData> getUserData(String userId) { ... }

@Bulkhead(value = 20)
@Timeout(value = 8, unit = ChronoUnit.SECONDS)
public Uni<Void> storeUserData(...) { ... }

@Bulkhead(value = 20)
@Timeout(value = 10000)
public Uni<Void> produceAccountingCDREvent(...) { ... }
```

### Reduce Kafka buffer
```yaml
buffer.memory: 16777216   # 16MB instead of 64MB
```

### Reduce consumer poll batch
```yaml
max.poll.records: 300   # Down from 1000
```

### Consider 2 cores per pod
A single core is very constrained for a reactive app doing Redis + Kafka + DB with fault tolerance thread overhead. 2 cores per pod would significantly reduce contention.
