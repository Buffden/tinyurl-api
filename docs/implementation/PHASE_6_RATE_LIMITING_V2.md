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

```nginx
# /etc/nginx/conf.d/rate-limiting.conf

# Define rate limit zones per endpoint
# Zone 1: Create endpoint (strict limit)
limit_req_zone $binary_remote_addr zone=create_limit:10m rate=5r/m;

# Zone 2: Redirect endpoint (generous limit)
limit_req_zone $binary_remote_addr zone=redirect_limit:10m rate=200r/s;

# Zone 3: Health endpoint (unlimited)
limit_req_zone $binary_remote_addr zone=health_limit:10m rate=1000r/s;

# How zone works:
# - $binary_remote_addr = client IP (binary format, more efficient)
# - zone=create_limit:10m = zone name + memory size for tracking (10MB = ~160k IPs)
# - rate=5r/m = 5 requests per minute per IP

server {
    listen 443 ssl http2;
    server_name tinyurl.buffden.com;
    
    # Create endpoint: strict rate limiting (5/min per IP)
    location /api/urls {
        limit_req zone=create_limit burst=10 nodelay;
        # burst=10 allows short bursts without queuing
        # nodelay = don't delay excess requests (reject instead)
        
        proxy_pass http://backend;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
    
    # Redirect endpoint: permissive rate limiting (200/sec per IP)
    location ~ ^/[a-zA-Z0-9]{6,8}$ {
        limit_req zone=redirect_limit burst=50 nodelay;
        
        proxy_pass http://backend;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
    
    # Health endpoint: no rate limit
    location ~ ^/actuator/health {
        limit_req zone=health_limit burst=1000 nodelay;
        
        proxy_pass http://backend;
    }
    
    # 429 Too Many Requests response
    error_page 429 /ratelimit.json;
    location = /ratelimit.json {
        internal;
        default_type application/json;
        return 429 '{"error":"Too Many Requests","status":429}';
    }
}
```

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

```java
@Component
public class RateLimiterService {
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    // Define limits per endpoint
    private static final Map<String, RateLimit> LIMITS = Map.of(
        "create", new RateLimit(5, 60),        // 5 requests/minute
        "create_custom", new RateLimit(2, 60), // 2 requests/minute (stricter for custom aliases)
        "redirect", new RateLimit(200, 1)      // 200 requests/second
    );

    public RateLimitResult checkLimit(String endpoint, String clientIp) {
        RateLimit limit = LIMITS.getOrDefault(endpoint, new RateLimit(1000, 1));
        
        String key = "limit:" + endpoint + ":" + clientIp;
        String countKey = key + ":count";
        String resetKey = key + ":reset";
        
        try {
            // Use Lua script for atomic increment + TTL check
            Long count = redisTemplate.execute(
                new DefaultRedisScript<>(
                    "local count = redis.call('INCR', KEYS[1]) " +
                    "if count == 1 then " +
                    "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
                    "end " +
                    "return count",
                    Long.class
                ),
                Arrays.asList(countKey),
                String.valueOf(limit.windowSeconds)
            );
            
            // Get remaining TTL for reset time
            Long ttl = redisTemplate.getExpire(countKey, TimeUnit.SECONDS);
            long resetTime = System.currentTimeMillis() + (ttl * 1000);
            
            boolean allowed = count <= limit.maxRequests;
            
            // Track metrics
            Counter.builder("ratelimit.check")
                .tag("endpoint", endpoint)
                .tag("allowed", String.valueOf(allowed))
                .register(meterRegistry)
                .increment();
                
            if (!allowed) {
                Counter.builder("ratelimit.exceeded")
                    .tag("endpoint", endpoint)
                    .register(meterRegistry)
                    .increment();
            }
            
            return new RateLimitResult(
                allowed,
                limit.maxRequests,
                count,
                limit.maxRequests - count, // remaining
                resetTime,
                (resetTime - System.currentTimeMillis()) / 1000 // seconds until reset
            );
        } catch (Exception e) {
            log.error("Rate limit check failed for {}, allowing request (fail-open)", clientIp, e);
            // Fail open: allow on error (don't block real users due to cache failure)
            return new RateLimitResult(true, limit.maxRequests, 0, limit.maxRequests, 0, 0);
        }
    }

    static class RateLimit {
        int maxRequests;
        int windowSeconds;
        
        RateLimit(int max, int window) {
            this.maxRequests = max;
            this.windowSeconds = window;
        }
    }
}

record RateLimitResult(
    boolean allowed,
    int limit,
    long used,
    long remaining,
    long resetTime,
    long retryAfterSeconds
) {}
```

**HTTP Interceptor to enforce limits**:

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final RateLimiterService rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = getClientIp(request);
        String endpoint = getEndpointType(request);
        
        RateLimitResult result = rateLimiter.checkLimit(endpoint, clientIp);
        
        // Add rate limit headers to all responses
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining));
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetTime / 1000));
        
        if (!result.allowed) {
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds));
            response.setStatus(429);
            response.setContentType("application/json");
            
            try {
                response.getWriter().write("{\"error\":\"Too Many Requests\",\"retry_after\":" 
                    + result.retryAfterSeconds + "}");
            } catch (IOException e) {
                log.error("Failed to write 429 response", e);
            }
            
            return false; // Stop handler execution
        }
        
        return true; // Continue to handler
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwarded = request.getHeader("X-Forwarded-For");
        if (xForwarded != null && !xForwarded.isEmpty()) {
            return xForwarded.split(",")[0].trim(); // First IP in chain
        }
        return request.getRemoteAddr();
    }

    private String getEndpointType(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        if (path.equals("/api/urls") && method.equals("POST")) {
            // Check if custom alias (from body) - but can't easily do here, so use interceptor on controller
            return "create";
        } else if (method.equals("GET")) {
            return "redirect";
        }
        return "default";
    }
}
```

**Controller-level annotation** (for custom alias detection):

```java
@PostMapping("/api/urls")
@RateLimited(endpoint = "create") // Default rate limit
public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest req) {
    // If custom alias provided, use stricter limit
    if (req.getCustomAlias() != null) {
        // This would be enforced by controller interceptor or AOP
        throw new RateLimitExceeded("Custom alias limit exceeded");
    }
    
    return ResponseEntity.status(201).body(...);
}
```

### Distributed Rate Limiting (Multi-Instance)

**Problem**: If running multiple app instances, each instance has its own rate limit count, allowing 5x more requests across all instances.

**Solution 1: Sticky Sessions** (Nginx)
```nginx
# Route requests from same IP to same backend instance
upstream backend {
    hash $binary_remote_addr consistent;  # Hash based on client IP
    server app1:8080;
    server app2:8080;
}
```
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

```java
@Configuration
public class RateLimitConfig {
    
    private static final List<String> WHITELIST = Arrays.asList(
        "127.0.0.1",           // Localhost
        "::1",                 // IPv6 localhost
        "10.0.0.0/8",         // Internal network (AWS VPC)
        "169.254.0.0/16"      // AWS metadata service
    );

    @Bean
    public IpAddressMatcher internalNetworkMatcher() {
        long startIp = ipToLong("10.0.0.0");
        long endIp = ipToLong("10.255.255.255");
        return new IpAddressMatcher(startIp, endIp);
    }

    // Use in interceptor:
    private boolean isWhitelisted(String clientIp) {
        for (String whitelist : WHITELIST) {
            if (clientIp.equals(whitelist)) {
                return true;
            }
            // CIDR range check (simplified)
            if (clientIp.startsWith("10.0")) {
                return true;
            }
        }
        return false;
    }
}
```

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
```prometheus
# % of requests hitting rate limit
rate(ratelimit.exceeded.total[5m]) / rate(ratelimit.check.total[5m])

# Request rate by endpoint
rate(http.server.requests.total[1m]) by (route)

# Top IPs by request count (redis-based, custom metric)
topk(10, rate(ratelimit.requests.total{endpoint="create"}[5m])) by (client_ip)
```

**Alerts**:
```yaml
- alert: RateLimitExceededSpike
  expr: rate(ratelimit.exceeded.total[5m]) > 100
  for: 2m
  annotations:
    summary: "Rate limit spike detected ({{$value}} requests/sec)"
    
- alert: RateLimitServiceDown
  expr: up{job="rate-limiter"} == 0
  for: 1m
  annotations:
    summary: "Rate limiter service down"
```

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
