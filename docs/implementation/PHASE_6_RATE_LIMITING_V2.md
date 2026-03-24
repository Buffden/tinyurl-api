# Phase 6: Rate Limiting (v2)

## Objective

Add layered rate limiting to protect create and redirect APIs from abuse while preserving availability. Includes Nginx edge limiting, app-layer token bucket, distributed coordination, and comprehensive monitoring.

## MVP-Only Execution Rule

- For the current timeline, implement only content before `## Optional (Post-MVP)`.
- Treat `## Optional (Post-MVP)` items as backlog and do not block release.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 5 Cache Layer (v2)](PHASE_5_CACHE_LAYER_V2.md)

## Source References

- [LLD Rate Limiting Module](../lld/rate-limiting-module.md)
- [Use Cases v2](../use-cases/v2/)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)
- [Threat Model](../security/threat-model.md)

## In Scope

- Nginx coarse-grained limit rules
- app-level token-bucket rules
- standardized 429 responses and headers

## Out of Scope

- user billing/quota system
- account-tier monetization policy

## Execution Steps

### Step 1: Edge/proxy limits

References:

- [LLD Rate Limiting Module](../lld/rate-limiting-module.md)

Tasks:

- Configure endpoint-specific Nginx rate rules
- Exclude health endpoint from rate limiting

### Step 2: App-layer token bucket limits

References:

- [LLD Rate Limiting Module](../lld/rate-limiting-module.md)
- [Use Cases v2](../use-cases/v2/)

Tasks:

- Implement per-IP/per-endpoint buckets
- Use Redis-backed state for distributed consistency

### Step 3: Client-facing error semantics

References:

- [LLD API Contract](../lld/api-contract.md)

Tasks:

- Return `429 Too Many Requests`
- Include `Retry-After` and limit headers

### Step 4: Validation under burst traffic

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

Tasks:

- Add integration tests for threshold crossings
- Verify normal traffic remains unaffected

## Deliverables

- Multi-layer rate limiting active
- 429 behavior contract-compliant
- Monitoring includes limit-hit metrics

## Acceptance Criteria

- Abusive request patterns are throttled
- Health endpoint remains accessible
- Legitimate traffic success rates remain acceptable

---

## Part 2: Implementation Deep Dive

### Layer 1: Nginx Edge Rate Limiting

**Nginx Configuration** (reverse proxy layer):
Define two `limit_req_zone` directives keyed on `$binary_remote_addr`: one for the create endpoint at 5 requests/minute, one for the redirect endpoint at 200 requests/second. Apply `limit_req` in the relevant `location` blocks with a burst buffer and `nodelay` so bursts are absorbed without queuing. Exclude `/actuator/health` from all rate-limit zones. Return a custom `429` status with a `Retry-After` header when limits are exceeded.

**Why these values:**
- Create: 5/min = ~1 per 12 seconds (prevent spam, allow legitimate users)
- Redirect: 200/sec = ample for 5K-20K QPS redirects per IP (AWS CloudFront IPs)
- Burst: allow temporary spikes without immediate rejection

**Limitations:**
- Per-IP only (no per-user without authentication)
- Shared state not across multiple Nginx instances (sticky sessions needed or sync)
- No circuit breaker (reject immediately at threshold)

### Layer 2: App-Level Token Bucket Rate Limiting

**Redis-backed Distributed Rate Limiter**:
Use a Redis counter per `IP:endpoint` key with an atomic Lua script: increment the counter and set its TTL on the first request within the window. If the counter exceeds the configured limit, return `false` (rate limited). The TTL is the sliding window duration (e.g., 60 seconds for the create endpoint). Using a Lua script ensures the increment-and-check is atomic and race-safe across multiple app instances.

**HTTP Interceptor to enforce limits**:
Implement a Spring `HandlerInterceptor` that runs `preHandle` for all API requests. Extract the client IP from `X-Forwarded-For` (with fallback to `RemoteAddr`), determine the endpoint bucket, call the Redis rate limiter, and return a `429` response with `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `Retry-After` headers if the limit is exceeded.

**Controller-level annotation** (for custom alias detection):
Add a custom `@RateLimit` annotation that can be placed on controller methods to specify a non-default limit (e.g., `2/min` for the custom alias path). The interceptor reads this annotation via `HandlerMethod` reflection to apply the stricter bucket instead of the endpoint default.

### Distributed Rate Limiting (Multi-Instance)

**Problem**: If running multiple app instances, each instance has its own rate limit count, allowing 5x more requests across all instances.

**Solution 1: Sticky Sessions** (Nginx):
Configure Nginx `ip_hash` directive to route the same client IP to the same upstream instance consistently. This ensures each instance's local counter reflects only that client's requests.

**Pros**: Simple, no coordination needed  
**Cons**: Uneven load distribution, connection persistence overhead

**Solution 2: Centralized Counter** (Redis) - RECOMMENDED
- All instances increment same Redis counter (already implemented above)
- TTL set in Redis (counter expires after window)
- Atomic Lua script prevents race conditions

**Solution 3: Token Bucket with Refresh Rate**
- Allocate X tokens per second globally
- Instances request tokens from Redis, not per-request
- Less precise but more efficient

**For Phase 6**: Use Solution 2 (Redis-based) already implemented above.

### Exemptions & Whitelisting

**Reserved IPs** (should not be rate-limited):
Whitelist the RFC-1918 private ranges `10.0.0.0/8`, `172.16.0.0/12`, and `192.168.0.0/16` so internal health probes, load balancer checks, and inter-service calls are never throttled. Maintain the whitelist in application config so it can be updated without a code deploy.

**Endpoint Exemptions**:
- Health checks: `/actuator/health*` (always allowed)
- Metrics: `/actuator/prometheus` (optional: allow from internal IP only)
- Admin endpoints: `/admin/*` (future, require API key)

### Rate Limit Monitoring & Alerts

**Key Metrics**:

| Metric | Description | Alert |
|--------|-----------|-------|
| `ratelimit.exceeded.total` | Requests rejected (429) | > 100/min = suspicious |
| `ratelimit.exceeded.by_endpoint` | Failed requests per endpoint | Track trend |
| `ratelimit.check.latency.p99` | P99 latency of rate limit check | > 50ms = Redis issue |
| `ratelimit.config.window_seconds` | Configured window per endpoint | Monitor for drift |

**Prometheus Query Examples**:
To monitor rejection rate, query `rate(ratelimit_exceeded_total[5m])` grouped by `endpoint`. To check Redis latency for rate limit checks, query `histogram_quantile(0.99, rate(ratelimit_check_duration_seconds_bucket[5m]))`. To detect sudden abuse bursts, alert when `rate(ratelimit_exceeded_total[1m]) > 100`.

**Alerts**:
Configure two Prometheus alert rules: one that fires `WARNING` when the rejection rate exceeds 100 per minute on any endpoint (possible abuse), and one that fires `CRITICAL` when the Redis rate-limit check P99 latency exceeds 50ms (indicates Redis pressure that could degrade all API traffic).

---

## Optional (Post-MVP)

- Add adaptive rate limits based on dynamic traffic baselines.
- Add per-user or API-key quotas when authentication is introduced.
- Add geo-based and ASN-based blocking at edge level.
- Add distributed global quotas across regions if multi-region is introduced.

## Deliverables

- [x] Nginx rate limiting configuration (per-endpoint rules)
- [x] Redis-backed token bucket rate limiter
- [x] App-level rate limit interceptor
- [x] 429 responses with Retry-After headers
- [x] Distributed rate limiting for multi-instance
- [x] Endpoint-specific limits (create: 5/min, redirect: 200/sec)
- [x] Custom alias stricter limit (2/min)
- [x] Whitelist/exemption for internal IPs
- [x] Health endpoint always accessible
- [x] Comprehensive metrics and alerting

## Acceptance Criteria

- [x] Create endpoint: 5/min per IP
- [x] Custom alias endpoint: 2/min per IP (stricter)
- [x] Redirect endpoint: 200/sec per IP
- [x] Health endpoint: no limit
- [x] 429 response includes X-RateLimit headers and Retry-After
- [x] Rate limiting works across multi-instance deployments
- [x] Cache/Redis failure doesn't block traffic (fail-open)
- [x] All rate limit checks tracked in metrics
- [x] Internal IPs (10.0.0.0/8) are whitelisted
- [x] Rate limit configuration per endpoint is documented
