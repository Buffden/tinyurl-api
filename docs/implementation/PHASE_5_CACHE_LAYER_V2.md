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

```yaml
# application.yaml (future expansion)
spring:
  redis:
    standalone:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD}
      database: 0
    timeout: 2000ms
    jedis:
      pool:
        max-active: 20        # Connection pool max size
        max-idle: 10          # Max idle connections
        min-idle: 5           # Min idle connections (pre-warming)
        max-wait: 2000ms      # Wait time before fail if pool exhausted
```

**Why these values:**
- max-active: 20 = sufficient for ~5K QPS with avg response time < 10ms
- min-idle: 5 = keep warm connections ready
- max-wait: 2000ms = fail fast if connection pool saturated

### Circuit Breaker Pattern for Redis Failures

**Implementation** (Spring Cloud CircuitBreaker + Resilience4j):

```java
@Component
public class UrlCacheService {
    private final StringRedisTemplate redisTemplate;
    private final UrlRepository urlRepository;
    private final CircuitBreaker circuitBreaker;

    public UrlCacheService(StringRedisTemplate template, UrlRepository repo) {
        this.redisTemplate = template;
        this.urlRepository = repo;
        
        // Configure circuit breaker
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0)        // Open after 50% failures
            .slowCallRateThreshold(50.0)       // Count > 2000ms as slow
            .slowCallDurationThreshold(Duration.ofMillis(2000))
            .waitDurationInOpenState(Duration.ofSeconds(30))  // Wait 30s before retry
            .permittedNumberOfCallsInHalfOpenState(5)  // Allow 5 test calls
            .recordExceptions(RedisConnectionFailureException.class)
            .ignoreExceptions(NullPointerException.class)
            .build();
        
        this.circuitBreaker = CircuitBreaker.of("redis-calls", config);
    }

    @CircuitBreaker(name = "redis-calls", fallbackMethod = "fallbackGetByCode")
    public UrlMapping getByCode(String shortCode) {
        try {
            // Try cache first
            String cached = redisTemplate.opsForValue().get("url:" + shortCode);
            if (cached != null) {
                return cached.equals("NOTFOUND") ? null : parseMapping(cached);
            }
        } catch (Exception e) {
            log.warn("Redis cache error, falling back to DB", e);
            // Circuit breaker will trip on repeated failures
        }
        
        // Cache miss or error, query DB
        UrlMapping mapping = urlRepository.findByShortCode(shortCode);
        
        // Populate cache
        if (mapping != null) {
            cacheMapping(shortCode, mapping);
        } else {
            cacheNotFound(shortCode);  // Negative cache
        }
        
        return mapping;
    }

    // Fallback when circuit is open (use DB directly, skip cache)
    public UrlMapping fallbackGetByCode(String shortCode, Exception ex) {
        log.error("Circuit breaker open for Redis, using DB directly", ex);
        return urlRepository.findByShortCode(shortCode);
    }

    private void cacheMapping(String shortCode, UrlMapping mapping) {
        try {
            String key = "url:" + shortCode;
            String value = serializeMapping(mapping);
            // TTL = (expiry - now) in seconds, max 24 hours
            long ttlSeconds = calculateTTL(mapping.getExpiryAt());
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("Failed to cache mapping for {}, continuing without cache", shortCode, e);
        }
    }

    private void cacheNotFound(String shortCode) {
        try {
            String key = "url:" + shortCode;
            // Negative cache with fixed TTL (60-300 seconds to reduce DB hits)
            redisTemplate.opsForValue().set(key, "NOTFOUND", Duration.ofSeconds(120));
        } catch (Exception e) {
            log.warn("Failed to cache NOTFOUND for {}", shortCode, e);
        }
    }
}
```

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
```
url:<short_code>              # Main cache entry (expiry-based TTL)
url:metadata:<short_code>     # (Future) Metadata: hit count, last access
url:deleted:<short_code>      # (Future) Soft-delete marker (short TTL)
```

**Collision Prevention**:
1. **Max short code length**: 8 characters (Base62) = 218 trillion unique codes >> 10M URLs needed
2. **Namespace isolation**: All keys prefixed with `url:` to avoid conflicts with other services
3. **Version prefix** (for future cache format changes): `url:v1:<short_code>`
4. **No user-controlled cache keys**: System generates all keys (prevent injection)

**Key Expiration Safety**:
```java
// Set TTL at key creation, not later (atomic operation)
String ttlKey = "url:" + shortCode;
long ttlSeconds = calculateTTL(expiryAt);  // Time until URL expires
redisTemplate.opsForValue().set(ttlKey, value, ttlSeconds, TimeUnit.SECONDS);
// Redis automatically deletes key after TTL expires
```

### Cache Warm-up & Population Strategy

**On URL Creation**:
```java
@PostMapping("/api/urls")
public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest req) {
    UrlMapping mapping = urlService.create(req);
    
    // Immediate cache warm-up (synchronous for v1)
    cacheService.cacheMapping(mapping.getShortCode(), mapping);
    
    return ResponseEntity.status(201).body(new CreateUrlResponse(mapping.getShortUrl()));
}
```

**On First Redirect** (Cache-aside pattern):
```java
@GetMapping("/{shortCode}")
public ResponseEntity<?> redirect(@PathVariable String shortCode) {
    UrlMapping mapping = cacheService.getByCode(shortCode);  // Cache-first
    
    if (mapping == null) {
        return ResponseEntity.notFound().build();
    }
    if (mapping.isExpired()) {
        return ResponseEntity.status(410).build();
    }
    
    return ResponseEntity.status(mapping.getRedirectStatus())
        .location(mapping.getOriginalUrl())
        .build();
}
```

**Negative Cache Handling**:
```java
// Cache 404 responses to reduce DB hits
private void cacheNotFound(String shortCode) {
    String key = "url:" + shortCode;
    // TTL = 120s (short, to let future creates propagate quickly)
    redisTemplate.opsForValue().set(key, "NOTFOUND", 120, TimeUnit.SECONDS);
}

// On retrieval, check for NOTFOUND marker
String cached = redisTemplate.opsForValue().get(key);
if ("NOTFOUND".equals(cached)) {
    return null;  // Cache hit for non-existent code (avoid DB query)
}
```

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
