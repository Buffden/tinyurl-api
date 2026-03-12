# Cache Module

> Cache-aside design for the redirect path. Introduced in **v2** — no cache exists in v1. This document covers the algorithm, key schema, TTL strategy, negative caching, invalidation, circuit breaker, and configuration.

---

## Overview

The redirect path (`GET /<short_code>`) is the dominant workload (99:1 read/write in v1, 95:5 in v2). At v2 scale (~5,000 avg QPS, 20K peak), hitting PostgreSQL on every redirect is not viable. A **cache-aside** layer in Redis absorbs repeat reads and keeps DB query rate below 10% of redirect traffic.

**Target**: cache hit ratio > 90% (NFR-P06).

---

## Cache-Aside Algorithm

### Read Path (Redirect)

```
1. Receive GET /<short_code>
2. Call cacheClient.get(short_code)
   ├── Cache HIT (positive)  → return UrlMapping, skip DB
   ├── Cache HIT (negative)  → return 404 immediately, skip DB
   └── Cache MISS             → continue to step 3
3. Call urlRepository.findByShortCode(short_code)
   ├── DB HIT (active)       → cache result (step 4), return UrlMapping
   ├── DB HIT (expired/deleted) → throw GoneException (do NOT cache expired state)
   └── DB MISS               → cache negative (step 5), throw NotFoundException
4. cacheClient.set(short_code, urlMapping, positiveTtl)
5. cacheClient.setNegative(short_code, negativeTtl)
```

### Write Path (Create)

```
1. Short URL created and persisted to PostgreSQL
2. cacheClient.set(short_code, urlMapping, positiveTtl)   // cache warming
```

Cache warming on create avoids a guaranteed cache miss on the first redirect for a newly created URL.

---

## Key Schema

| Key Pattern | Value | Purpose |
|---|---|---|
| `url:{short_code}` | JSON-serialized `UrlMapping` | Positive cache entry |
| `url:{short_code}` | `__NEG__` (sentinel string) | Negative cache entry (code does not exist) |

- **Prefix**: `url:` — namespaces TinyURL keys away from any other Redis data.
- **Example**: `url:aB3xYz` → `{"shortCode":"aB3xYz","originalUrl":"https://example.com/...","expiresAt":"2026-09-08T12:00:00Z","deleted":false}`

### Serialization

- Format: JSON via Jackson (`ObjectMapper`).
- `UrlMapping` is serialized/deserialized — never the JPA entity.
- `RedisTemplate<String, String>` with `StringRedisSerializer` for both key and value. JSON is stored as a plain string — no Java-specific binary serialization.

---

## TTL Strategy

### Positive Entries

| Parameter | Config Key | Default | Rationale |
|---|---|---|---|
| Base TTL | `tinyurl.cache.positive-ttl` | `1h` | Balances freshness with hit ratio. Most URLs are accessed in bursts. |
| Jitter range | `tinyurl.cache.positive-jitter` | `±10%` | Prevents thundering herd when many keys expire simultaneously. |
| Max TTL | — | `24h` | Upper bound. No cache entry survives longer than 24 hours regardless of jitter. |

**Jitter calculation**: `ttl = baseTtl + random(-jitter, +jitter)`.  
Example: base 3600s ± 10% → random TTL between 3240s and 3960s.

### Negative Entries

| Parameter | Config Key | Default | Rationale |
|---|---|---|---|
| Base TTL | `tinyurl.cache.negative-ttl` | `60s` | Short-lived — prevents repeated DB misses from bots or scanners without blocking newly created codes for long. |
| Jitter range | `tinyurl.cache.negative-jitter` | `±20%` | Wider jitter for short TTLs. |

### Why Not Cache Expired/Deleted URLs?

Expired and deleted URLs return HTTP 410, which is a terminal state. Caching them provides marginal benefit (they are low-traffic by definition) and introduces staleness risk if a future version supports un-deleting or renewal.

---

## Negative Caching

When a `short_code` is not found in the DB, a **negative cache entry** is stored to prevent repeated DB lookups for codes that don't exist.

- **Value**: the sentinel string `__NEG__` (not JSON — `get()` checks for this before attempting deserialization).
- **TTL**: 60s default (configurable). Short enough that a newly created URL is accessible within a minute even if a negative entry was cached moments before creation.
- **Race condition**: A `POST /api/urls` that creates a new code while a negative entry exists is safe — the write path calls `cacheClient.set()` which overwrites the negative entry with the real mapping.

---

## Cache Invalidation

### When to Invalidate

| Event | Action | Reason |
|---|---|---|
| URL created | `set(code, mapping, positiveTtl)` | Warm the cache; also overwrites any stale negative entry |
| URL soft-deleted (v2) | `delete(code)` | Prevent serving a deleted URL from cache |
| Cleanup job removes expired rows | `delete(code)` per batch | Stale positive entries must not outlive DB cleanup |

### When NOT to Invalidate

- **On expiry check during redirect**: the service checks `expiresAt` in the cached `UrlMapping` object itself. If expired, it throws `GoneException` and does **not** delete the cache entry — the TTL will handle eviction naturally.
- **On read miss**: no action on the cache for a DB hit — only `set()` to populate.

### Invalidation Method

`RedisCacheClient.delete(String code)` → `redisTemplate.delete("url:" + code)`.

No pub/sub or cache bus. With a single Redis instance (v2 scope), direct `DELETE` is sufficient.

---

## Circuit Breaker

Redis is a **performance optimization**, not a correctness requirement. If Redis is unavailable, the system must degrade gracefully to DB-only operation.

### Configuration

| Parameter | Config Key | Default | Purpose |
|---|---|---|---|
| Failure threshold | `tinyurl.cache.circuit-breaker.failure-threshold` | `5` | Number of consecutive failures to trip the breaker |
| Wait duration | `tinyurl.cache.circuit-breaker.wait-duration` | `30s` | Time in OPEN state before transitioning to HALF-OPEN |
| Permitted calls in half-open | `tinyurl.cache.circuit-breaker.half-open-calls` | `3` | Probe calls allowed in HALF-OPEN to test recovery |

### State Machine

```
CLOSED ──(5 consecutive failures)──→ OPEN
OPEN ──(30s elapsed)──→ HALF-OPEN
HALF-OPEN ──(3 successful probes)──→ CLOSED
HALF-OPEN ──(any failure)──→ OPEN
```

### Implementation

- Library: **Resilience4j** `CircuitBreaker` (already in the Spring Boot ecosystem).
- Wraps all `RedisTemplate` calls inside `RedisCacheClient`.
- When the breaker is OPEN, `get()` returns `Optional.empty()` (cache miss) and `set()`/`setNegative()`/`delete()` are no-ops.
- Circuit breaker state transitions are logged at `WARN` level and exposed as Micrometer metrics (`cache_circuit_breaker_state`).

### Failure Handling per Operation

| Operation | On Redis failure | Effect |
|---|---|---|
| `get()` | Return `Optional.empty()` | Falls through to DB — correct but slower |
| `set()` | Swallow exception, log WARN | DB has the data; next read will cache-miss and retry |
| `setNegative()` | Swallow exception, log WARN | DB lookup happens on next request — acceptable |
| `delete()` | Swallow exception, log WARN | Stale entry expires via TTL — eventually consistent |

---

## `application.yml` Configuration

```yaml
tinyurl:
  cache:
    enabled: true                          # Master switch — false disables all cache calls
    key-prefix: "url:"
    positive-ttl: 1h
    positive-jitter: 0.1                   # ±10%
    negative-ttl: 60s
    negative-jitter: 0.2                   # ±20%
    circuit-breaker:
      failure-threshold: 5
      wait-duration: 30s
      half-open-calls: 3

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 500ms                       # Connection + command timeout
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 4
```

---

## Metrics

Exposed via Micrometer for Prometheus scraping.

| Metric | Type | Labels | Purpose |
|---|---|---|---|
| `cache_requests_total` | Counter | `result={hit,miss,negative_hit}` | Cache access breakdown |
| `cache_hit_ratio` | Gauge | — | Rolling hit ratio (derived from counters) |
| `cache_latency_seconds` | Histogram | `operation={get,set,delete}` | Redis call latency |
| `cache_errors_total` | Counter | `operation={get,set,delete}` | Redis failures (pre-circuit-breaker) |
| `cache_circuit_breaker_state` | Gauge | `state={closed,open,half_open}` | Current breaker state (1 = active) |

---

## Interface Contract

From [module-structure.md](module-structure.md) — repeated here for completeness.

```java
public interface CacheClient {
    Optional<UrlMapping> get(String code);
    void set(String code, UrlMapping mapping, Duration ttl);
    void setNegative(String code, Duration ttl);
    void delete(String code);
}
```

- `get()` returns `Optional.empty()` on cache miss **or** Redis failure.
- `set()` / `setNegative()` / `delete()` are fire-and-forget from the caller's perspective — exceptions are handled internally.
- `delete()` is added beyond the original module-structure contract for explicit invalidation on soft-delete and cleanup.

---

## Activation

The cache module is gated behind Spring profile and property:

- **Profile**: `v2` — `RedisConfig` and `RedisCacheClient` beans are annotated `@Profile("v2")`.
- **Property**: `tinyurl.cache.enabled=true` — `@ConditionalOnProperty` on the `RedisCacheClient` bean. Allows disabling cache in v2 without changing the profile (useful for debugging).
- **v1 behavior**: No `CacheClient` bean exists. `UrlServiceImpl` checks for null injection (`@Autowired(required = false)`) and skips all cache calls.

---

## Docker Compose

```yaml
redis:
  image: redis:7-alpine
  ports:
    - "6379:6379"
  command: redis-server --maxmemory 128mb --maxmemory-policy allkeys-lru
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s
    timeout: 3s
    retries: 3
  restart: unless-stopped
```

- **Eviction policy**: `allkeys-lru` — when memory is full, Redis evicts the least recently used key. Safe because the cache is a derived dataset; the DB is the source of truth.
- **Max memory**: 128 MB for local dev. Production sizing is based on working set (est. 50K–100K hot entries × ~200 bytes = ~10–20 MB; 128 MB provides ample headroom).
