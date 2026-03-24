# Rate Limiting Module

> Multi-layer rate limiting to protect the system from abuse. **v1** uses Nginx-level throttling only. **v2** adds application-layer token bucket limiting backed by Redis. This document covers both layers, configuration, headers, and failure handling.

---

## Overview

Rate limiting serves two purposes:

1. **Abuse prevention** — stop a single IP from consuming disproportionate capacity (bot spikes, credential stuffing of aliases).
2. **System protection** — prevent cascading failure under load spikes that exceed provisioned capacity.

The strategy is **defense in depth**: Nginx rejects the cheapest (L7 proxy) and the app layer enforces fine-grained, per-endpoint limits with proper HTTP semantics.

---

## Layer 1: Nginx Rate Limiting (v1 + v2)

Nginx `limit_req` provides coarse per-IP throttling at the reverse proxy. This is the first line of defense — requests rejected here never reach the JVM.

### Configuration

```nginx
# Define rate limit zones
limit_req_zone $binary_remote_addr zone=create_zone:10m rate=10r/m;
limit_req_zone $binary_remote_addr zone=redirect_zone:10m rate=200r/s;

server {
    # Create endpoint — strict limit
    location /api/urls {
        limit_req zone=create_zone burst=5 nodelay;
        limit_req_status 429;
        proxy_pass http://app;
    }

    # Redirect endpoint — generous limit
    location / {
        limit_req zone=redirect_zone burst=50 nodelay;
        limit_req_status 429;
        proxy_pass http://app;
    }

    # Health endpoint — no rate limiting
    location /actuator/health {
        limit_req off;
        proxy_pass http://app;
    }
}
```

### Parameters

| Zone | Endpoint | Rate | Burst | Memory | Rationale |
|---|---|---|---|---|---|
| `create_zone` | `POST /api/urls` | 10 req/min per IP | 5 | 10 MB (~160K IPs) | URL creation is write-heavy and expensive; 10/min is generous for legitimate use |
| `redirect_zone` | `GET /<short_code>` | 200 req/s per IP | 50 | 10 MB (~160K IPs) | Redirects are cheap (cache-backed in v2) but must cap per-IP to prevent single-source floods |

### Limitations of Nginx-Only

- No differentiation between endpoints beyond location blocks.
- No `Retry-After` header (Nginx returns a plain 429 body).
- No rate limit headers (`X-RateLimit-*`).
- Cannot share state across multiple Nginx instances (each instance tracks independently).
- Cannot enforce per-endpoint-per-IP granularity beyond what location blocks provide.

These gaps are why Layer 2 exists in v2.

---

## Layer 2: Application Rate Limiting (v2 only)

### Library: Bucket4j

**Bucket4j 8.x** with the `bucket4j-redis` extension (Lettuce backend). Chosen because:

- Token bucket algorithm is a natural fit for bursty traffic patterns.
- Redis backend shares state across all app instances.
- Spring Boot integration via `@Component` filter — no framework lock-in.
- Configurable per endpoint, per IP, with independent bucket parameters.

### Token Bucket Algorithm

Each (IP, endpoint) pair gets a virtual bucket:

```
Bucket capacity: N tokens
Refill rate: R tokens per T interval
On request:
  if bucket.tryConsume(1):
    allow request
  else:
    reject with 429
```

Tokens refill at a fixed rate. Bursts are allowed up to the bucket capacity, then requests are throttled to the refill rate.

### Bucket Configuration

| Endpoint | Capacity | Refill Rate | Refill Interval | Effective Steady-State | Rationale |
|---|---|---|---|---|---|
| `POST /api/urls` | 5 | 5 | 1 minute | 5 req/min per IP | Matches Nginx layer; app layer is the enforcement authority |
| `POST /api/urls` (with alias) | 2 | 2 | 1 minute | 2 req/min per IP | Custom aliases are scarce and expensive to validate (uniqueness check) |
| `GET /<short_code>` | 100 | 100 | 1 second | 100 req/s per IP | Generous for legitimate use; catches automated scraping |
| `GET /actuator/health` | — | — | — | Not rate-limited | Health probes must never be throttled |

### Key Schema in Redis

| Key Pattern | Value | TTL |
|---|---|---|
| `rl:{endpoint}:{ip}` | Bucket4j binary state | Matches the refill interval + buffer |

- **Prefix**: `rl:` — namespaces rate limit keys separately from cache keys (`url:`).
- **Example**: `rl:create:203.0.113.42` — bucket state for the create endpoint for IP `203.0.113.42`.
- **TTL**: Bucket keys auto-expire when inactive. Default: 2× the refill interval (e.g., 2 minutes for the create bucket). Prevents unbounded key growth from transient IPs.

---

## HTTP Response for Rate-Limited Requests

### Status Code

**HTTP 429 Too Many Requests** — per RFC 6585.

### Response Body

```json
{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "Rate limit exceeded. Try again in 45 seconds."
}
```

Follows the standard error response schema from [api-contract.md](api-contract.md).

### Rate Limit Headers

Returned on **every response** (not just 429s) so clients can self-throttle:

| Header | Value | Example | Standard |
|---|---|---|---|
| `X-RateLimit-Limit` | Bucket capacity | `5` | draft-ietf-httpapi-ratelimit-headers |
| `X-RateLimit-Remaining` | Tokens remaining | `3` | draft-ietf-httpapi-ratelimit-headers |
| `X-RateLimit-Reset` | Seconds until bucket refill | `45` | draft-ietf-httpapi-ratelimit-headers |
| `Retry-After` | Seconds to wait (429 only) | `45` | RFC 7231 §7.1.3 |

`Retry-After` is only included on 429 responses. The `X-RateLimit-*` headers are included on all responses to give clients visibility into their remaining quota.

---

## Implementation

### `RateLimitFilter`

A Spring `OncePerRequestFilter` registered in `WebConfig` at order 4 (after `LoggingFilter`, `MetricsFilter`, `RecoveryFilter`).

```
1. Extract client IP from request (X-Forwarded-For header, falling back to remote address)
2. Determine endpoint category (create, create-with-alias, redirect, health)
3. If health → skip rate limiting, continue filter chain
4. Resolve bucket for (endpoint, ip) from Redis via Bucket4j
5. Call bucket.tryConsume(1)
   ├── Allowed → add X-RateLimit-* headers, continue filter chain
   └── Denied  → set 429 status, add Retry-After + X-RateLimit-* headers, write error response body, return
```

### IP Extraction

```java
private String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
        return xff.split(",")[0].trim();  // first IP = original client
    }
    return request.getRemoteAddr();
}
```

- **Trust boundary**: Nginx sets `X-Forwarded-For`. The app trusts the first entry because Nginx is the only proxy in the deployment (single-hop). If the architecture adds more proxies, this logic must be updated.
- **IPv6**: `getRemoteAddr()` and `X-Forwarded-For` handle IPv6 natively. Bucket keys use the full IP string.

### Endpoint Categorization

| Request Pattern | Category | Bucket Config |
|---|---|---|
| `POST /api/urls` without `alias` field | `create` | 5/min |
| `POST /api/urls` with `alias` field | `create-with-alias` | 2/min |
| `GET /<short_code>` (path has no `/api` prefix) | `redirect` | 100/s |
| `GET /actuator/health` | `health` | No limit |
| Everything else | `create` (default) | 5/min (safe fallback) |

Note: differentiating `create` vs `create-with-alias` requires inspecting the request body. The filter reads the body only for `POST /api/urls` requests and checks for the presence of the `alias` field. The body is wrapped in a `ContentCachingRequestWrapper` so downstream controllers can still read it.

---

## Circuit Breaker for Redis (Rate Limiting)

Rate limiting Redis calls share the same Resilience4j circuit breaker pattern as the cache module, but with a **separate breaker instance** (`rateLimitCircuitBreaker`).

### Failure Behavior

| Breaker State | Behavior | Rationale |
|---|---|---|
| CLOSED | Normal operation — Redis-backed rate limiting | — |
| OPEN | **Allow all requests through** (no rate limiting) | Failing open is safer than blocking all traffic. A Redis outage should not cause a system-wide denial of service. Nginx Layer 1 still provides basic protection. |
| HALF-OPEN | Probe with a few rate limit checks | Standard recovery probing |

**Critical design choice**: the rate limiter **fails open**, unlike a typical circuit breaker that fails closed. Blocking all requests when Redis is down would be a self-inflicted denial of service. The Nginx layer provides a safety net.

### Configuration

| Parameter | Config Key | Default |
|---|---|---|
| Failure threshold | `tinyurl.rate-limit.circuit-breaker.failure-threshold` | `5` |
| Wait duration | `tinyurl.rate-limit.circuit-breaker.wait-duration` | `30s` |
| Half-open calls | `tinyurl.rate-limit.circuit-breaker.half-open-calls` | `3` |

---

## `application.yml` Configuration

```yaml
tinyurl:
  rate-limit:
    enabled: true                                # Master switch — false disables app-layer rate limiting
    buckets:
      create:
        capacity: 5
        refill-tokens: 5
        refill-period: 1m
      create-with-alias:
        capacity: 2
        refill-tokens: 2
        refill-period: 1m
      redirect:
        capacity: 100
        refill-tokens: 100
        refill-period: 1s
    key-prefix: "rl:"
    key-ttl-multiplier: 2                        # Key TTL = refill-period × multiplier
    circuit-breaker:
      failure-threshold: 5
      wait-duration: 30s
      half-open-calls: 3
```

---

## Metrics

Exposed via Micrometer for Prometheus scraping.

| Metric | Type | Labels | Purpose |
|---|---|---|---|
| `rate_limit_requests_total` | Counter | `endpoint={create,create_alias,redirect}`, `result={allowed,denied}` | Total requests processed by the rate limiter |
| `rate_limit_denied_total` | Counter | `endpoint={create,create_alias,redirect}` | 429 responses issued |
| `rate_limit_latency_seconds` | Histogram | `endpoint` | Time spent in rate limit check (Redis round-trip) |
| `rate_limit_circuit_breaker_state` | Gauge | `state={closed,open,half_open}` | Current breaker state |

---

## Activation

- **v1**: Nginx-only rate limiting. No `RateLimitFilter` bean. The filter class exists in the codebase but is gated with `@Profile("v2")` and `@ConditionalOnProperty("tinyurl.rate-limit.enabled")`.
- **v2**: Both layers active. Nginx provides the outer defense; `RateLimitFilter` provides fine-grained, stateful limiting with proper HTTP headers.

---

## Interaction with Other Middleware

From [module-structure.md](module-structure.md), the filter chain order is:

| Order | Filter | Interaction with Rate Limiting |
|---|---|---|
| 1 | `LoggingFilter` | Logs the request before rate limiting — all requests (including 429s) get a correlation ID and request log |
| 2 | `MetricsFilter` | Records the request before rate limiting — 429s are counted in overall request metrics |
| 3 | `RecoveryFilter` | Catches unexpected exceptions from the rate limit filter |
| 4 | `RateLimitFilter` | **This module** — rejects requests here if over limit |

Rate-limited requests (429) are logged and metered before rejection. They do not reach controllers or the service layer.
