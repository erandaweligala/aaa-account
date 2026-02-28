# Memory Usage Analysis - AAA Accounting Service

## Problem
Pods consistently hitting ~1GB memory (1021Mi-1022Mi), approaching 2GB container limit.

## Root Cause: No JVM Heap Limit + Significant Off-Heap Allocations

### 1. No `-Xmx` Configured
The Dockerfile (`dockerfile_active:34`) sets `JAVA_OPTS=""` (empty). With no explicit heap limit, JVM 21 (Amazon Corretto) default ergonomics allocates ~25% of container RAM as max heap (~500MB on a 2GB pod).

### 2. Memory Breakdown (Estimated per Pod)

| Component | Estimated Memory | Source |
|---|---|---|
| JVM Heap (default ergonomics) | ~500MB | No `-Xmx` set, JVM defaults to ~25% of 2GB |
| 4 Kafka Producers buffers | ~64MB | 4 × `buffer.memory: 16777216` (16MB each) |
| Kafka Consumer fetch buffers | ~57MB | `fetch.max.bytes: 52428800` (52MB) + socket buffers |
| JVM Metaspace | ~80-100MB | Quarkus, Kafka, Redis, Oracle driver classes (no `MaxMetaspaceSize` set) |
| Thread Stacks | ~32MB | 32 max-threads × 1MB default stack size |
| DB Connection Pool | ~25-30MB | 25 connections + `prepared-statement-cache-max-size: 256` |
| Redis Connection Pool | ~10-15MB | 48 connections |
| Netty/Vert.x Direct Buffers | ~40-50MB | HTTP, Kafka, Redis I/O (off-heap) |
| JIT Code Cache | ~48MB | Default ReservedCodeCacheSize |
| Async Log Queues | ~5-10MB | Console: 4096 entries + File: 8192 entries |
| MessageTemplate In-Memory Cache | ~10-20MB | ConcurrentHashMap, max 10,000 entries |
| **TOTAL ESTIMATED** | **~900MB - 1050MB** | **Matches observed ~1022Mi** |

### 3. Key Configuration Issues

#### a. Dockerfile - No JVM Memory Flags
```dockerfile
# Current (dockerfile_active:34)
JAVA_OPTS=""
```
No `-Xmx`, `-Xms`, `-XX:MaxMetaspaceSize`, or `-XX:MaxDirectMemorySize` set.

#### b. Kafka Producer Buffers (application.yml:196,214,227,240)
```yaml
buffer.memory: 16777216  # 16MB × 4 producers = 64MB total
```

#### c. Kafka Consumer Fetch Size (application.yml:256-258)
```yaml
fetch.max.bytes: 52428800          # 52MB - very large
max.partition.fetch.bytes: 5242880 # 5MB per partition
receive.buffer.bytes: 131072       # 128KB socket buffer
```

#### d. Unbounded Metaspace
No `-XX:MaxMetaspaceSize` means metaspace grows indefinitely as classes are loaded.

## Recommended Fix

### Option A: Set JVM Memory Limits (Recommended)

Update Dockerfile `JAVA_OPTS` to explicitly control memory:

```dockerfile
JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=128m -XX:+UseG1GC -XX:+UseStringDeduplication"
```

This caps JVM heap at 512MB, metaspace at 128MB, and direct memory at 128MB, leaving headroom for native/off-heap allocations within 2GB container.

### Option B: Reduce Kafka Buffer Memory

Reduce per-producer buffer from 16MB to 8MB:
```yaml
buffer.memory: 8388608  # 8MB × 4 = 32MB total (saves 32MB)
```

Reduce consumer fetch size:
```yaml
fetch.max.bytes: 10485760          # 10MB instead of 52MB
max.partition.fetch.bytes: 1048576 # 1MB instead of 5MB
```

### Option C: Combined (Best)

Apply both Option A and Option B together:
- Total estimated memory with fixes: ~700-750MB (well within 2GB limit)
- Provides ~1.2GB headroom for burst traffic

## Quick Verification Commands

```bash
# Check current heap settings inside running pod
oc exec <pod-name> -- jcmd 1 VM.flags | grep -i heap

# Check metaspace usage
oc exec <pod-name> -- jcmd 1 GC.heap_info

# Monitor memory over time
oc exec <pod-name> -- jcmd 1 VM.native_memory summary

# Check Kafka producer buffer usage (via Prometheus metrics)
curl <pod-ip>:9905/q/metrics | grep kafka_producer_buffer
```
