# URL Shortener - Architecture Documentation

## System Design Overview

This document explains the architecture, design decisions, and trade-offs in the URL Shortener service.

```
┌─────────────────────────────────────────────────────────────────┐
│                         Client Layer                             │
│  Web Browser | Mobile App | API Client | REST Client             │
└────────────────────────┬────────────────────────────────────────┘
                         │ HTTP/HTTPS
┌────────────────────────▼────────────────────────────────────────┐
│                    Load Balancer (Nginx)                         │
│  - Rate limiting (IP-based)                                      │
│  - SSL/TLS termination                                           │
│  - Compression (Gzip)                                            │
│  - Health checks                                                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────────┐
│            Spring Boot Microservice Cluster                      │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Request Processing Pipeline                 │   │
│  │  1. Rate Limiter (Token Bucket - Redis)                 │   │
│  │  2. Request Validation                                   │   │
│  │  3. Cache Check (L1: Caffeine → L2: Redis)              │   │
│  │  4. Database Query (if not cached)                       │   │
│  │  5. Analytics Event (async to RabbitMQ)                 │   │
│  │  6. Response Serialization                               │   │
│  └──────────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
┌───────▼────┐    ┌──────▼──────┐   ┌────▼─────────┐
│PostgreSQL  │    │   Redis     │   │ Elasticsearch│
│(Primary DB)│    │(Cache/RL)   │   │ (Analytics)  │
└────────────┘    └─────────────┘   └──────────────┘
```

## Core Components

### 1. **Snowflake ID Generator**

#### Problem Solved
- Need distributed unique ID generation without centralized DB dependency
- IDs should be sortable by timestamp (useful for analytics)
- No collision even with concurrent requests across multiple servers

#### Solution: Snowflake Algorithm
```
64-bit Long ID Structure:
├─ 1 bit:   Sign (unused, always 0)
├─ 41 bits: Timestamp (ms since custom epoch)
├─ 5 bits:  Data Center ID (0-31)
├─ 5 bits:  Machine ID (0-31)
└─ 12 bits: Sequence number (0-4095)
```

#### Key Benefits
- **4096 unique IDs per millisecond per machine** - handles burst traffic
- **Non-sequential but sortable** - IDs increase with time
- **Horizontal scalability** - add more machines without coordination
- **Collision-free** - deterministic generation

#### Example
```
Snowflake ID: 9223372036854775807
Components:
  - Timestamp: 1693219200000 (Aug 28, 2023)
  - DataCenter: 1
  - Machine: 1
  - Sequence: 1

Base62 Encoded: "abc123xyz"
```

### 2. **Multi-Layer Caching Strategy**

#### Three-Tier Cache Architecture

```
Request → L1 Cache (Caffeine) → L2 Cache (Redis) → L3 (Database)
                 ↓                   ↓                    ↓
            <1ms latency        1-5ms latency        10-50ms latency
            Local, in-memory    Distributed, shared   Source of truth
            Per-instance        All instances         Persistent
```

#### Layer Details

**L1: In-Memory Cache (Caffeine)**
- **Where**: Local to each service instance
- **TTL**: 5 minutes
- **Size**: 10,000 entries
- **Latency**: <1ms
- **Trade-off**: Inconsistency across instances (OK for short URLs)

**L2: Distributed Cache (Redis)**
- **Where**: Central Redis instance
- **TTL**: 60 minutes
- **Latency**: 1-5ms (network hop)
- **Benefit**: Consistency across all instances

**L3: Database (PostgreSQL)**
- **Where**: Persistent storage
- **Source of truth**: Authoritative data
- **Latency**: 10-50ms (with indexes)

#### Write Flow
```
Create Short URL
  │
  ├─ Save to Database (L3)
  │
  ├─ Write to Redis (L2)
  │
  ├─ Write to Caffeine (L1)
  │
  └─ Return response
```

#### Read Flow
```
Resolve Short Code
  │
  ├─ Check Caffeine (L1) → if hit, return
  │
  ├─ Check Redis (L2) → if hit, populate L1, return
  │
  ├─ Query Database (L3) → if hit, populate L1+L2, return
  │
  └─ Not found error
```

#### Cache Invalidation
- **Soft TTL**: Automatic expiration (5min L1, 60min L2)
- **Hard invalidation**: When URL is deleted
- **Cascade invalidation**: Both L1 and L2 cleared

### 3. **Rate Limiting**

#### Token Bucket Algorithm

```
Bucket State:
- Capacity: 100 tokens
- Refill Rate: 100 tokens/minute
- Current Tokens: varies

Request arrives:
  ├─ If tokens > 0:
  │   ├─ Consume 1 token
  │   └─ Allow request ✅
  │
  └─ If tokens == 0:
      └─ Reject request ❌ (429 Too Many Requests)

Refill happens:
  - Every minute: add (elapsed_time * refill_rate / 60000) tokens
  - Cap at capacity (don't exceed max)
```

#### Implementation
- **Storage**: Redis (distributed state)
- **Scope**: Per IP address
- **Burst Handling**: Full bucket allows immediate burst
- **Graceful Degradation**: Falls back to allowing if Redis fails
- **TTL**: 24-hour key expiration to prevent stale keys

#### Example Timeline
```
Time 0s:   Bucket full (100 tokens)
Time 0s:   Request 1 arrives → consume 1 token (99 left)
Time 0.01s:Request 2 arrives → consume 1 token (98 left)
...
Time 1s:   100 requests received → bucket empty (0 left)
Time 1.6s: Tokens refilled: 100 * (0.6s / 60s) ≈ 1 token
Time 2s:   Tokens refilled: 100 * (2s / 60s) ≈ 3 tokens
```

### 4. **Analytics Pipeline**

#### Async Event Processing

```
1. URL Resolved
   ├─ Return original URL immediately (fast path)
   │
   └─ Publish click event to RabbitMQ (async)
       │
       2. Message Queue Consumer
       │
       ├─ Enrich event (Geo-IP lookup, etc.)
       │
       ├─ Index in Elasticsearch
       │
       └─ Update statistics in PostgreSQL
           (daily aggregations, top referrers, etc.)
```

#### Benefits
- **Non-blocking**: Analytics don't slow down redirects
- **Scalable**: Multiple consumers can process events
- **Reliable**: Messages guaranteed to be processed (with dead letter queues)
- **Real-time**: Near-immediate analytics update

#### Data Collected
- IP address & geo-location
- User agent & device type
- Referrer URL
- Timestamp
- Click count (aggregated)

### 5. **Horizontal Sharding (Optional)**

#### Why Sharding
- **Scale writes**: Write-heavy workload
- **Scale reads**: Geographic distribution
- **Isolation**: Failure in one shard doesn't affect others

#### Sharding Strategy
```
Shard Key: hash(short_code) % num_shards

Example with 3 shards:
├─ Shard 1: short_codes where hash % 3 == 0
├─ Shard 2: short_codes where hash % 3 == 1
└─ Shard 3: short_codes where hash % 3 == 2
```

#### Implementation
- Consistent hashing prevents rehashing all data on scale
- Separate database per shard (or separate schemas)
- Router layer (in application) determines shard
- Cross-shard queries (e.g., user's URL list) handled at application layer

#### Trade-offs
- **Pros**: Horizontal scalability, isolated failures
- **Cons**: Operational complexity, cross-shard queries harder

## Database Design

### Schema

```sql
shortened_urls
├─ id (BIGINT PK)          -- Snowflake ID
├─ short_code (VARCHAR)    -- Base62 encoded, unique index
├─ original_url (TEXT)     -- The long URL
├─ custom_alias (VARCHAR)  -- User-provided alias
├─ user_id (VARCHAR)       -- Who created it
├─ total_clicks (INT)      -- Click counter
├─ expires_at (TIMESTAMP)  -- Expiration time
├─ is_active (BOOLEAN)     -- Soft delete flag
├─ created_at (TIMESTAMP)  -- Creation time
└─ updated_at (TIMESTAMP)  -- Last update time

click_analytics
├─ id (BIGSERIAL PK)       -- Event ID
├─ short_code (VARCHAR)    -- Reference
├─ ip_address (VARCHAR)    -- Source
├─ country (VARCHAR)       -- Geo data
├─ city (VARCHAR)          -- Geo data
├─ referrer (VARCHAR)      -- HTTP referer
├─ device (VARCHAR)        -- Device type
├─ browser (VARCHAR)       -- Browser type
├─ latitude/longitude      -- GPS coordinates
└─ clicked_at (TIMESTAMP)  -- Event time
```

### Indexes

**Primary Lookups (write-once, read-many)**
- `short_code` (UNIQUE) - fastest path
- `custom_alias` (UNIQUE) - alternative lookup

**Filtering & Aggregation (analytics)**
- `expires_at` - find expired URLs for cleanup
- `user_id` - list URLs by user
- `created_at` - time-based queries
- `is_active` - active URLs only

**Analytics**
- `(short_code, clicked_at)` - composite for time-series
- `country` - geographic breakdown
- `clicked_at` - time-based aggregations

### Query Patterns

**High-frequency (99% of requests)**
```sql
SELECT original_url FROM shortened_urls 
WHERE short_code = ? AND is_active = true AND expires_at > now();
```
→ Uses `short_code` index, returns single row

**Moderate-frequency (admin/analytics)**
```sql
SELECT country, COUNT(*) FROM click_analytics 
WHERE short_code = ? AND clicked_at > now() - interval '1 day'
GROUP BY country;
```
→ Uses `(short_code, clicked_at)` index

**Low-frequency (cleanup, reporting)**
```sql
DELETE FROM shortened_urls WHERE expires_at < now();
UPDATE shortened_urls SET total_clicks = ? WHERE short_code = ?;
```
→ Batch operations during off-peak

## Performance Characteristics

### Read Path (URL Resolution)

```
Scenario: 100k concurrent users, each making 1 request/sec

Without cache:
├─ 100k queries/sec to database
├─ Database CPU @ 95%
├─ Average latency: 250ms
└─ Users experience timeouts ❌

With multi-layer cache:
├─ 99% L1 hit rate (Caffeine)
├─ 0.5% L2 hit rate (Redis)
├─ 0.5% L3 hit rate (Database)
├─ Average latency: 2ms
├─ Database CPU @ 5%
└─ Smooth performance ✅
```

### Write Path (URL Creation)

```
Request Rate: 1000 URLs/second

Bottleneck: Database writes
Solution: 
  ├─ Connection pooling (20 connections)
  ├─ Batch inserts where possible
  ├─ Async analytics (off main path)
  └─ Can handle 5000+ writes/sec with single instance
```

### Throughput Targets

| Operation | p50 | p95 | p99 | RPS Capacity |
|-----------|-----|-----|-----|--------------|
| URL Creation | 30ms | 50ms | 100ms | 1,000+ |
| URL Redirect | 3ms | 5ms | 10ms | 10,000+ |
| Analytics Query | 50ms | 100ms | 200ms | 1,000+ |

## Scalability Strategies

### Vertical Scaling
- Increase JVM heap size (16GB → 32GB)
- Enable more application threads
- Upgrade database server (more CPU/RAM)
- Increases capacity until resource limits hit

### Horizontal Scaling
- Add more service instances behind load balancer
- Share Redis cache (all instances use same Redis)
- Database read replicas for analytics queries
- Implement sharding for write-heavy workloads

### Database Scaling
```
Read-heavy analytics:
├─ Primary: handles writes (URL creation)
└─ Replica: handles reads (analytics queries)

Write-heavy at scale (>1000 writes/sec):
├─ Implement sharding
├─ Each shard has own primary + replicas
└─ Application layer routes to correct shard
```

## Trade-offs & Design Decisions

### Decision 1: Snowflake vs Sequential IDs

| Aspect | Snowflake | Sequential |
|--------|-----------|-----------|
| Distributed | ✅ No coordination | ❌ Requires central service |
| Collision risk | ❌ Rare | ✅ None |
| Sortability | ✅ By timestamp | ✅ Perfect |
| Implementation | ⚠️ More complex | ✅ Simple |
| **Chosen** | ✅ Yes | ❌ No |

**Why**: Snowflake enables true distributed system without single point of failure

### Decision 2: Cache Invalidation Strategy

| Strategy | Pros | Cons |
|----------|------|------|
| Time-based (TTL) | ✅ Simple, no coordination | ❌ Stale data possible |
| Event-based | ✅ Immediate consistency | ❌ Complex, error-prone |
| Hybrid (TTL + events) | ✅ Best of both | ⚠️ Higher complexity |
| **Chosen** | | Hybrid |

**Why**: TTL ensures eventual consistency, events for critical updates

### Decision 3: Analytics (Sync vs Async)

| Approach | Sync | Async |
|----------|------|-------|
| Latency | ❌ Adds 100ms+ | ✅ <5ms impact |
| Accuracy | ✅ No data loss | ⚠️ Possible loss |
| Complexity | ✅ Simple | ❌ Message queue |
| **Chosen** | ❌ No | ✅ Yes |

**Why**: Redirects must be fast, analytics can be slightly delayed

## Failure Scenarios & Resilience

### Scenario 1: Redis Cache Down

```
Request arrives
├─ L1 cache → MISS (first request)
├─ L2 cache → DOWN (Redis unavailable)
├─ Try database → SUCCESS
└─ Cache result in local L1 only
   (Degraded performance, but still working) ✅
```

**Mitigation**: Sentinel nodes for automatic failover

### Scenario 2: Database Down

```
Request arrives
├─ L1 cache → HIT (URL was accessed before)
└─ Return from cache → Works! ✅

Request arrives (first time)
├─ L1 cache → MISS
├─ L2 cache → Maybe HIT
├─ Database → DOWN
└─ Return 503 Service Unavailable ❌
```

**Mitigation**: Read replicas with automatic failover

### Scenario 3: Single Instance Crash

```
Load Balancer
├─ Instance 1 → DOWN (crashed)
├─ Instance 2 → UP (handles requests)
├─ Instance 3 → UP (handles requests)
└─ Redis cache → UP (shared)
   Users seamlessly rerouted to working instances ✅
```

**No data loss** because shared cache and persistent database

## Monitoring & Observability

### Metrics to Track

**System Health**
- CPU, Memory, Disk usage
- JVM heap size, GC frequency
- Database connection pool usage
- Redis memory usage

**Application Performance**
- Request latency (p50, p95, p99)
- Throughput (requests/sec)
- Error rate (4xx, 5xx)
- Cache hit rate (L1, L2)

**Business Metrics**
- URLs created/day
- Total clicks/day
- Top countries by clicks
- URL expiration rate

### Alerts

Critical:
- Database connection pool exhausted
- Redis down
- Error rate > 1%
- p95 latency > 100ms

Warning:
- Memory usage > 80%
- Cache hit rate < 80%
- Service instance down

## Cost Optimization

### Database
- Read replicas only for read-heavy queries
- Archive old analytics data to S3
- Implement TTL-based automatic cleanup

### Cache
- Tune L1 cache size (10k vs 50k entries trade-off)
- Monitor Redis memory usage
- Implement cache warming for popular URLs

### Infrastructure
- Use spot instances for non-critical workers
- Auto-scaling based on request rate
- Reserved instances for baseline capacity

## Future Improvements

1. **API Versioning**: Support v2, v3 as API evolves
2. **Custom Redirects**: A/B testing, analytics dashboard
3. **Webhooks**: Notify users on URL milestones
4. **QR Codes**: Auto-generate QR codes for short URLs
5. **URL Preview**: Generate previews before redirecting
6. **URL Encryption**: Support password-protected URLs
7. **Bulk Operations**: Import/export URLs in bulk
8. **Custom Domains**: Allow users to use their own domain

## References

- [Twitter's Snowflake Algorithm](https://github.com/twitter-archive/snowflake/tree/snowflake-2010)
- [Designing Data-Intensive Applications by Martin Kleppmann]()
- [System Design Interview by Alex Xu]()
