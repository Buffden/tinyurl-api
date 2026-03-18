# Phase 5: Cache Layer (v2)

## Objective

Introduce Redis cache-aside on the redirect path to reduce database load and improve read latency. Includes connection pooling, circuit breaker patterns, and negative caching strategies.

## MVP-Only Execution Rule

- For the current timeline, implement only content before `## Optional (Post-MVP)`.
- Treat `## Optional (Post-MVP)` items as backlog and do not block release.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 3 Observability](PHASE_3_OBSERVABILITY.md)

## Source References

- [LLD Cache Module](../lld/cache-module.md)
- [Use Cases v2](../use-cases/v2/)
- [HLD Database v2](../hld/database/v2/database-design.md)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

## In Scope

- Redis integration
- cache-aside reads on redirect path
- negative caching and TTL policy
- cache failure fallback to DB

## Out of Scope

- custom alias logic
- rate-limiting logic

## Execution Steps

### Step 1: Redis integration and configuration

References:

- [LLD Cache Module](../lld/cache-module.md)
- [HLD Database v2](../hld/database/v2/database-design.md)

Tasks:

- Add Redis client configuration and connection management
- Add environment-driven timeout and retry defaults

### Step 2: Cache-aside read flow

References:

- [Use Case: Redirect URL v2](../use-cases/v2/UC-US-002-redirect-url.md)

Tasks:

- Lookup short code in cache first
- On miss, read DB and populate cache

### Step 3: Negative caching and TTL policy

References:

- [LLD Cache Module](../lld/cache-module.md)

Tasks:

- Store null/missing markers with short TTL
- Use TTL + jitter for stable eviction patterns

### Step 4: Resilience behavior

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

Tasks:

- Implement graceful fallback to DB on cache outage
- Track cache hit/miss/error metrics

## Deliverables

- Redirect flow supports cache hit and miss paths
- Negative caching active for unknown codes
- Observability includes cache performance metrics

---

## Part 2: Implementation Deep Dive

### Redis Architecture Decision: Cluster vs Standalone

**Standalone** (Recommended for v1/v2)
- **Pros**: Simpler operations, no cross-slot key issues, single point of configuration
- **Cons**: Single point of failure, limited by single-node memory
- **When**: Suitable for < 100 GB cache, acceptable failover time
- **HA Strategy**: Sentinel mode (Redis master + replicas + sentinel monitors)

**Cluster Mode** (For future high-scale deployment)
- **Pros**: Horizontal sharding, no single point of failure, scales beyond single node
- **Cons**: Complex operations, cross-slot limitations (keys must hash to same slot), network overhead
- **When**: Cache > 200 GB or requires zero-downtime failover
- **Recommendation**: Defer to post-v2 if capacity analysis shows need

**For Phase 5**: Use **Standalone + Sentinel** (compromise: simple ops + HA)

### Connection Pool Configuration

**Redis Connection Pool Settings** (Lettuce or Jedis):
Configure the pool with `max-active: 20`, `min-idle: 5`, `max-idle: 10`, and `max-wait: 2000ms`. Set `connect-timeout` to 500ms and `command-timeout` to 1000ms. These values are defined in `application.yaml` under `spring.data.redis` and overridden per environment via environment variables.

**Why these values:**
- max-active: 20 = sufficient for ~5K QPS with avg response time < 10ms
- min-idle: 5 = keep warm connections ready
- max-wait: 2000ms = fail fast if connection pool saturated

### Circuit Breaker Pattern for Redis Failures

**Implementation** (Spring Cloud CircuitBreaker + Resilience4j):
Wrap all Redis read/write calls inside a `CircuitBreaker` bean named `redis-calls`. Configure it to open after a 50% failure rate over a 10-call sliding window, stay open for 30 seconds, then transition to half-open and allow 3 trial calls. On open, the fallback immediately queries the database directly. Register the circuit breaker via `Resilience4jCircuitBreakerFactory` and expose its state metric via the existing Micrometer/Actuator integration.

**Metrics to track**:
- `circuitbreaker.calls.total{name="redis-calls", state="success|failure|ignored"}`
- `circuitbreaker.state{name="redis-calls"}` (0=closed, 1=open, 2=half-open)
- `cache.hit.ratio` / `cache.miss.ratio`
- `redis.connection.pool.used` / `redis.connection.pool.max`

### Cache Invalidation Strategy

**TTL-based (Recommended for v1/v2)**
- **Strategy**: Each cache entry has automatic TTL based on URL expiry
- **TTL Formula**: `ttlSeconds = (expiryAt - now) in seconds`, capped at 24 hours max
- **Negative Cache TTL**: 120 seconds (short, to unblock stale 404 caches)
- **Pros**: No additional coordination, simple, automatic cleanup
- **Cons**: Up to TTL delay before DB changes visible

**Event-based (Future enhancement)**
- **Strategy**: On create/update, publish cache invalidation events
- **Mechanism**: Redis Pub/Sub or Kafka topic for all instances
- **TTL Formula**: `ttlSeconds = (expiryAt - now)`, no fixed max
- **Pros**: Immediate consistency across instances
- **Cons**: Requires message broker, more operational complexity

**Hybrid (Recommended upgrade path)**
- **TTL-based as default**: Automatic expiry per URL lifespan
- **Event-based for explicit updates**: When soft-delete or manual operations change state
- **Fallback**: Always query DB after cache miss within X seconds (safety check)

**For Phase 5**: Implement TTL-based invalidation. Code event-based architecture but defer activation to Phase 9 (hardening).

### Cache Key Naming Convention & Collision Prevention

**Key Format**:
Use the pattern `url:v1:<short_code>` for all cache entries (e.g., `url:v1:aB3xYz`). The `url:` namespace isolates URL mappings from any other Redis keys. The `v1:` version prefix allows future cache schema changes to be rolled out without collisions by bumping the version and running both keys in parallel during migration.

**Collision Prevention**:
1. **Max short code length**: 8 characters (Base62) = 218 trillion unique codes >> 10M URLs needed
2. **Namespace isolation**: All keys prefixed with `url:` to avoid conflicts with other services
3. **Version prefix** (for future cache format changes): `url:v1:<short_code>`
4. **No user-controlled cache keys**: System generates all keys (prevent injection)

**Key Expiration Safety**:
When writing a cache entry, compute the TTL as `(expiresAt - now)` in seconds, capped at 86400 seconds (24 hours). Never write a key without a TTL to prevent stale entries accumulating indefinitely. If `expiresAt` is already in the past, skip the cache write entirely and return the DB result directly.

### Cache Warm-up & Population Strategy

**On URL Creation**:
After persisting a new URL mapping to the database, immediately write the mapping to the cache using the computed TTL. This pre-warms the cache so the first redirect request hits a cache entry rather than the database.

**On First Redirect** (Cache-aside pattern):
On a cache miss, read the mapping from the database, write it to the cache with the remaining TTL, then return the redirect. All subsequent redirects for the same short code will be served from cache until the TTL expires.

**Negative Cache Handling**:
If a short code is not found in the database (genuine 404), write a sentinel value (e.g., the string `"NOT_FOUND"`) to the cache under the same key with a 120-second TTL. On subsequent requests, treat the sentinel as a 404 response without querying the database, preventing repeated DB lookups for non-existent codes.

### Monitoring & Metrics

**Key Metrics to Track**:

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| `cache.hit.count` | Successful cache hits (should be > 90% of traffic) | < 80% = investigate |
| `cache.miss.count` | Cache misses (first access or expired) | Monitor trend |
| `cache.error.count` | Redis connection/operation errors | > 0 = alert |
| `redis.memory.used` | Current Redis memory consumption | > 80% = scale |
| `redis.memory.max` | Redis max memory configured | Trending up = plan expansion |
| `redis.connection.pool.active` | Currently active connections | > 15 = connection leak |
| `redis.command.duration.p99` | P99 latency for Redis operations | > 50ms = performance issue |
| `circuitbreaker.state` | Circuit breaker state (0=closed, 1=open, 2=half-open) | = 1 = redis problem |

---

## Optional (Post-MVP)

- Move from standalone Redis to Redis Cluster after measured capacity pressure.
- Add event-driven cache invalidation for explicit update/delete flows.
- Add Redis Sentinel/HA automation runbook with failover drills.
- Add advanced cache dashboards (cardinality-heavy drilldown panels).

## Deliverables

- [x] Redis integration with connection pooling configuration
- [x] Cache-aside pattern implemented for redirects
- [x] Circuit breaker for Redis failure resilience
- [x] TTL-based cache invalidation strategy
- [x] Negative caching for 404 responses (120s TTL)
- [x] Cache key naming convention with collision prevention
- [x] Metrics for cache hit/miss/error rates
- [x] Comprehensive monitoring setup
- [x] Standalone + Sentinel architecture recommendation
- [x] Event-based invalidation architecture (deferred to Phase 9)

## Acceptance Criteria

- [x] Cache hit ratio > 90% under normal traffic
- [x] Redis outage does not cause 5xx errors (graceful fallback to DB)
- [x] Circuit breaker opens after 50% failure rate, closes after 30s retry succeeds
- [x] Negative cache TTL 120s (verified in integration tests)
- [x] All cache operations tracked in metrics
- [x] Cache key collisions impossible (namespace isolation)
- [x] Cache memory usage stays < 80% of configured max
- [x] P99 cache latency < 50ms (Redis + network)
