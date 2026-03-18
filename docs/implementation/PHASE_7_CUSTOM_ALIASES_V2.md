# Phase 7: Custom Aliases (v2)

## Objective

Add feature-flagged custom alias support with strict validation, uniqueness guarantees, and correct conflict semantics.

## MVP-Only Execution Rule

- For the current timeline, implement only content before `## Optional (Post-MVP)`.
- Treat `## Optional (Post-MVP)` items as backlog and do not block release.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 5 Cache Layer (v2)](PHASE_5_CACHE_LAYER_V2.md)
- [Phase 6 Rate Limiting (v2)](PHASE_6_RATE_LIMITING_V2.md)

## Source References

- [Use Case: Custom Alias v2](../use-cases/v2/UC-US-004-custom-alias.md)
- [LLD API Contract](../lld/api-contract.md)
- [Functional Requirements](../requirements/functional-requirements.md)
- [Rate Limiting Module](../lld/rate-limiting-module.md)

## In Scope

- custom alias field support in create flow
- feature flag gating for rollout safety
- alias validation, reserved words, and uniqueness checks

## Out of Scope

- alias marketplace/transfer features
- user-managed namespace hierarchies

## Execution Steps

### Step 1: Feature flag integration

References:

- [Use Case: Custom Alias v2](../use-cases/v2/UC-US-004-custom-alias.md)

Tasks:

- Add feature flag for alias behavior
- Ensure disabled path returns correct error

### Step 2: Validation rules and reserved namespace

References:

- [LLD API Contract](../lld/api-contract.md)

Tasks:

- Validate charset and length constraints
- Enforce reserved alias list rules

### Step 3: Uniqueness and conflict behavior

References:

- [Use Case: Custom Alias v2](../use-cases/v2/UC-US-004-custom-alias.md)

Tasks:

- Ensure alias uniqueness checks are race-safe
- Return `409 Conflict` for taken aliases

### Step 4: Rate and abuse controls for alias endpoint

References:

- [LLD Rate Limiting Module](../lld/rate-limiting-module.md)

Tasks:

- Apply stricter limits for alias creation path
- Validate 429 contract behavior for alias route

## Deliverables

- Custom alias create path available behind feature flag
- Validation and conflict semantics complete
- Tests for enabled/disabled and conflict scenarios

## Acceptance Criteria

- Alias creation works when feature flag is enabled
- Disabled state and invalid alias cases return correct errors
- Alias conflicts deterministically return 409
- Stricter rate limits (2/min) enforced for alias creation path
- Existing aliases remain unaffected when custom alias is not supplied

---

## Part 2: Implementation Deep Dive

### Feature Flag Architecture

**Spring Boot Conditional Property Pattern:**

```yaml
# application.yaml (dev/test - enabled by default)
features:
  custom-alias:
    enabled: true

# application-prod.yaml (production - controlled rollout)
features:
  custom-alias:
    enabled: false  # Enable via environment override for gradual rollout
```

**Feature Flag Configuration Class:**

```java
@Configuration
@EnableWebMvc
public class FeatureFlagConfig {

    @Bean
    public FeatureFlagProvider featureFlagProvider(@Value("${features.custom-alias.enabled:false}") 
                                                   boolean customAliasEnabled) {
        return new FeatureFlagProvider(customAliasEnabled);
    }
}

@Component
public class FeatureFlagProvider {
    private final boolean customAliasEnabled;
    private static final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    public FeatureFlagProvider(boolean customAliasEnabled) {
        this.customAliasEnabled = customAliasEnabled;
        Counter.builder("feature_flag_check")
            .tag("flag", "custom_alias")
            .tag("enabled", String.valueOf(customAliasEnabled))
            .register(meterRegistry);
    }

    public boolean isCustomAliasEnabled() {
        Counter.builder("feature_flag_evaluated")
            .tag("flag", "custom_alias")
            .tag("result", customAliasEnabled ? "enabled" : "disabled")
            .register(meterRegistry);
        return customAliasEnabled;
    }
}
```

**API Controller with Feature Flag Guard:**

```java
@RestController
@RequestMapping("/api/urls")
public class UrlControllerV2 {
    private final FeatureFlagProvider featureFlagProvider;
    private final CreateUrlService createUrlService;
    private final MeterRegistry meterRegistry;

    @PostMapping
    public ResponseEntity<?> createUrl(@RequestBody CreateUrlRequest request) {
        // Attempt custom alias creation
        if (request.getCustomAlias() != null) {
            if (!featureFlagProvider.isCustomAliasEnabled()) {
                meterRegistry.counter("custom_alias_feature_flag_disabled").increment();
                return ResponseEntity.badRequest().body(ApiError.builder()
                    .code("FEATURE_UNAVAILABLE")
                    .message("Custom aliases are not yet available")
                    .build());
            }
        }

        Url url = createUrlService.createUrl(request);
        meterRegistry.counter("url_created_success")
            .tag("custom_alias_used", String.valueOf(request.getCustomAlias() != null))
            .increment();
        return ResponseEntity.status(HttpStatus.CREATED).body(url);
    }
}
```

**Fallback Behavior When Disabled:**

```java
// Always generates system-generated alias when feature flag is OFF
// User's custom alias request is silently ignored or returns helpful error
if (!featureFlagProvider.isCustomAliasEnabled() && request.getCustomAlias() != null) {
    log.info("Custom alias requested but feature disabled. Proceeding with system-generated alias.");
    request.setCustomAlias(null);  // Clear custom alias
}
```

---

### Custom Alias Validation Rules

**Validation Constraints (RFC-adherent):**

| Constraint | Value | Rationale |
| --- | --- | --- |
| Minimum length | 4 characters | Prevents single-char typos, domain-grabbing |
| Maximum length | 32 characters | Reasonable API contract, memorable for users |
| Character set | [a-zA-Z0-9_-] (Base62 + hyphen/underscore) | URL-safe, human-readable |
| Reserved words | 200+ (see below) | Prevent namespace conflicts |
| Reserved prefixes | admin, api, app, internal | Block internal routes |

**Validator Implementation:**

```java
@Component
public class CustomAliasValidator {
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{4,32}$");
    private static final Set<String> RESERVED_WORDS = Set.of(
        // System routes
        "api", "admin", "app", "internal", "management", "health", "metrics", "actuator",
        // Common service names
        "www", "mail", "ftp", "blog", "wiki", "forum", "shop", "cdn", "static",
        // Geographic/marketing
        "help", "about", "contact", "privacy", "terms", "support", "careers",
        // Analytics/tracking
        "tracking", "analytics", "pixel", "banner", "ad", "ads",
        // Length-2 shortcuts that might be TLDs or acronyms
        "ai", "io", "co", "me", "tv", "us", "uk", "de", "fr", "it", "es", "ru"
    );
    private static final Set<String> RESERVED_PREFIXES = Set.of(
        "admin_", "api_", "app_", "internal_", "test_"
    );
    private final MeterRegistry meterRegistry;

    public ValidationResult validate(String customAlias, long creatorUserId) {
        List<String> errors = new ArrayList<>();

        // 1. Format validation
        if (customAlias == null || customAlias.trim().isEmpty()) {
            errors.add("Alias cannot be empty");
            incrementMetric("validation_failure", "empty");
            return new ValidationResult(false, errors);
        }

        customAlias = customAlias.trim();

        // 2. Pattern validation
        if (!ALIAS_PATTERN.matcher(customAlias).matches()) {
            errors.add(String.format(
                "Alias must be 4-32 characters in [a-zA-Z0-9_-]. Got: %s",
                customAlias
            ));
            incrementMetric("validation_failure", "pattern_mismatch");
            return new ValidationResult(false, errors);
        }

        // 3. Reserved word check (case-insensitive)
        String lowerAlias = customAlias.toLowerCase();
        if (RESERVED_WORDS.contains(lowerAlias)) {
            errors.add(String.format(
                "Alias '%s' is reserved for system use",
                customAlias
            ));
            incrementMetric("validation_failure", "reserved_word");
            return new ValidationResult(false, errors);
        }

        // 4. Reserved prefix check
        for (String prefix : RESERVED_PREFIXES) {
            if (lowerAlias.startsWith(prefix)) {
                errors.add(String.format(
                    "Alias '%s' starts with reserved prefix '%s'",
                    customAlias, prefix
                ));
                incrementMetric("validation_failure", "reserved_prefix");
                return new ValidationResult(false, errors);
            }
        }

        // 5. Length boundaries with warnings
        if (customAlias.length() < 4) {
            errors.add("Alias too short (minimum 4 characters)");
            incrementMetric("validation_failure", "too_short");
        }
        if (customAlias.length() > 32) {
            errors.add("Alias too long (maximum 32 characters)");
            incrementMetric("validation_failure", "too_long");
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors);
        }

        incrementMetric("validation_success", "passed_all_checks");
        return new ValidationResult(true, Collections.emptyList());
    }

    private void incrementMetric(String metricName, String tag) {
        Counter.builder("alias_validation")
            .tag("result", metricName)
            .tag("reason", tag)
            .register(meterRegistry)
            .increment();
    }
}

public class ValidationResult {
    private final boolean valid;
    private final List<String> errors;

    public ValidationResult(boolean valid, List<String> errors) {
        this.valid = valid;
        this.errors = errors;
    }

    public boolean isValid() { return valid; }
    public List<String> getErrors() { return errors; }
}
```

---

### Race-Safe Uniqueness Validation

**Database Schema (with UNIQUE constraint):**

```sql
-- PostgreSQL
ALTER TABLE urls ADD CONSTRAINT uq_urls_custom_alias UNIQUE (custom_alias) WHERE custom_alias IS NOT NULL;

-- Index for lookups
CREATE INDEX idx_urls_custom_alias ON urls(custom_alias) WHERE custom_alias IS NOT NULL;

-- Partial index avoids NULL checks (multiple NULL values are allowed)
-- Lookups: SELECT * FROM urls WHERE custom_alias = ?
```

**Conflict Detection at Application Layer:**

```java
@Service
public class CreateUrlService {
    private final UrlRepository urlRepository;
    private final CustomAliasValidator aliasValidator;
    private final MeterRegistry meterRegistry;
    private final CacheService cacheService;

    public Url createUrl(CreateUrlRequest request) throws ConflictException {
        // 1. Validate custom alias if provided
        if (request.getCustomAlias() != null) {
            ValidationResult validation = aliasValidator.validate(request.getCustomAlias(), request.getUserId());
            if (!validation.isValid()) {
                throw new InvalidAliasException(validation.getErrors());
            }
        }

        try {
            // 2. Create URL entity with custom alias (or let DB generate system alias)
            Url url = new Url();
            url.setTargetUrl(request.getTargetUrl());
            url.setExpiryTime(request.getExpiryTime());
            url.setCustomAlias(request.getCustomAlias());  // May be null
            url.setCreatedAt(LocalDateTime.now());
            
            // 3. Save to database (UNIQUE constraint enforced by DB)
            Url savedUrl = urlRepository.save(url);
            
            // 4. Warm cache with new URL
            cacheService.put(savedUrl.getAlias(), savedUrl, savedUrl.getTTL());
            
            meterRegistry.counter("url_created")
                .tag("has_custom_alias", String.valueOf(request.getCustomAlias() != null))
                .increment();
            
            return savedUrl;

        } catch (DataIntegrityViolationException e) {
            // 5. Catch UNIQUE constraint violation (race condition or duplicate)
            meterRegistry.counter("alias_conflict_detected").increment();
            
            // Logging with correlation ID for debugging
            log.warn("Alias conflict detected for custom_alias='{}' user_id={}",
                request.getCustomAlias(), request.getUserId(), e);
            
            // Return deterministic 409 Conflict response
            throw new ConflictException(String.format(
                "Alias '%s' already in use. Try another.",
                request.getCustomAlias()
            ));
        }
    }

    // Custom exceptions
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public static class InvalidAliasException extends RuntimeException {
        private final List<String> errors;

        public InvalidAliasException(List<String> errors) {
            super("Invalid alias: " + String.join(", ", errors));
            this.errors = errors;
        }

        public List<String> getErrors() { return errors; }
    }
}
```

**Exception Handler for Deterministic HTTP Responses:**

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CreateUrlService.ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(CreateUrlService.ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.builder()
            .code("ALIAS_CONFLICT")
            .message(ex.getMessage())
            .details("The requested alias is already in use by another URL")
            .build());
    }

    @ExceptionHandler(CreateUrlService.InvalidAliasException.class)
    public ResponseEntity<ApiError> handleInvalidAlias(CreateUrlService.InvalidAliasException ex) {
        return ResponseEntity.badRequest().body(ApiError.builder()
            .code("INVALID_ALIAS")
            .message("Alias validation failed")
            .details(String.join("; ", ex.getErrors()))
            .build());
    }
}
```

---

### Stricter Rate Limiting for Custom Alias Endpoint

**Nginx Rate Limiting Layer (edge):**

```nginx
# In nginx.conf or within server block

# Define rate limit zones with stricter limits for alias creation
limit_req_zone $binary_remote_addr zone=alias_create_limit:10m rate=2r/m;
limit_req_zone $binary_remote_addr zone=public_create_limit:10m rate=5r/m;

# Alias endpoint: 2 requests per minute (stricter for abuse prevention)
location /api/urls {
    # Check if alias field present in request (heuristic)
    if ($request_body ~* "customAlias") {
        limit_req zone=alias_create_limit burst=2 nodelay;
    }
    
    # Fallback to standard rate limit
    limit_req zone=public_create_limit burst=10 nodelay;
    
    proxy_pass http://backend;
}
```

**Application Layer Rate Limiting with Custom Alias Detection:**

```java
@Component
public class AliasRateLimitInterceptor implements HandlerInterceptor {
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;
    private static final String ALIAS_RATE_LIMIT_KEY = "rate_limit:alias:{}";
    private static final long ALIAS_REQUESTS_PER_MINUTE = 2;
    private static final long STANDARD_REQUESTS_PER_MINUTE = 5;
    private static final long WINDOW_SECONDS = 60;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) 
            throws Exception {
        
        // Only apply to POST /api/urls with custom alias
        if (!isAliasCreationRequest(request)) {
            return true;
        }

        String clientIp = extractClientIp(request);
        String rateLimitKey = String.format(ALIAS_RATE_LIMIT_KEY, clientIp);

        try {
            // 1. Get current request count
            String countStr = redisTemplate.opsForValue().get(rateLimitKey);
            long currentCount = countStr != null ? Long.parseLong(countStr) : 0;

            // 2. Check limit (stricter for alias)
            if (currentCount >= ALIAS_REQUESTS_PER_MINUTE) {
                meterRegistry.counter("rate_limit_exceeded")
                    .tag("type", "alias")
                    .tag("client_ip", clientIp)
                    .increment();

                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", "60");
                response.setHeader("X-RateLimit-Limit", String.valueOf(ALIAS_REQUESTS_PER_MINUTE));
                response.setHeader("X-RateLimit-Remaining", "0");
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many alias creation requests. " +
                    "Try again in 60 seconds.\"}"
                );
                return false;
            }

            // 3. Increment counter
            redisTemplate.opsForValue().increment(rateLimitKey);
            redisTemplate.expire(rateLimitKey, Duration.ofSeconds(WINDOW_SECONDS));

            // 4. Set response headers
            response.setHeader("X-RateLimit-Limit", String.valueOf(ALIAS_REQUESTS_PER_MINUTE));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(ALIAS_REQUESTS_PER_MINUTE - currentCount - 1));
            response.setHeader("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + WINDOW_SECONDS));

            meterRegistry.counter("rate_limit_allowed")
                .tag("type", "alias")
                .tag("remaining", String.valueOf(ALIAS_REQUESTS_PER_MINUTE - currentCount - 1))
                .increment();

            return true;

        } catch (Exception e) {
            log.error("Rate limiter error for alias creation", e);
            // Fail open: allow request if Redis fails
            meterRegistry.counter("rate_limiter_error").increment();
            return true;
        }
    }

    private boolean isAliasCreationRequest(HttpServletRequest request) throws IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return false;
        if (!request.getRequestURI().equals("/api/urls")) return false;
        
        // Check if request body contains customAlias field
        String body = getRequestBody(request);
        return body.contains("customAlias");
    }

    private String getRequestBody(HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String extractClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "CF-Connecting-IP", "X-Real-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isEmpty()) {
                return value.split(",")[0];
            }
        }
        return request.getRemoteAddr();
    }
}

// Register interceptor
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AliasRateLimitInterceptor aliasRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(aliasRateLimitInterceptor)
            .addPathPatterns("/api/urls");
    }
}
```

---

### Rollout Safety Notes

- No backfill migration is required for v2 custom aliases.
- Existing v1 short URLs continue using generated aliases.
- `custom_alias` remains optional and is written only for new requests where explicitly provided.
- Rollout plan: enable feature flag in dev, then staging, then production.
- Rollback plan: disable feature flag; existing custom aliases continue to resolve.

---

### Monitoring & Observability

**Metrics Dashboard (Grafana):**

```yaml
# prometheus-rules.yml
groups:
  - name: custom_alias
    interval: 30s
    rules:
      # Feature flag adoption
      - alert: CustomAliasFeatureFlagDisabled
        expr: rate(feature_flag_evaluated{flag="custom_alias", result="disabled"}[5m]) > 0
        for: 5m
        annotations:
          summary: "Custom alias feature flag is disabled"

      # Validation failures
      - alert: HighAliasValidationFailureRate
        expr: rate(alias_validation{result="validation_failure"}[5m]) > 0.1
        for: 5m
        annotations:
          summary: "High alias validation failure rate (>10%)"

      # Alias conflicts
      - alert: AliasConflictDetected
        expr: rate(alias_conflict_detected[1m]) > 0
        for: 2m
        annotations:
          summary: "Alias conflicts detected (possible race condition)"

      # Rate limit hits
      - alert: HighAliasRateLimitExceeded
        expr: rate(rate_limit_exceeded{type="alias"}[5m]) > 1
        for: 5m
        annotations:
          summary: "High rate limit exceeded events for alias creation"

# Grafana dashboard panels
Panels:
  - Feature Flag Toggle Status: gauge(feature_flag_check{flag="custom_alias", enabled="true"})
  - Alias Creation Success Rate: rate(url_created[5m]) offset by alias creation rate
  - Validation Failure Breakdown: alias_validation{result="validation_failure"} by reason
  - Conflict Rate: rate(alias_conflict_detected[5m])
  - Rate Limit Exceeded: rate(rate_limit_exceeded{type="alias"}[5m])
  - Custom Alias Usage Rate: rate(url_created_success{custom_alias_used="true"}[5m])
```

**Logging with Correlation IDs:**

```java
// In CreateUrlService and validators
MDC.put("operation", "create_url");
MDC.put("has_custom_alias", String.valueOf(request.getCustomAlias() != null));
MDC.put("client_ip", extractClientIp(request));

log.info("Creating URL with custom alias: {}", request.getCustomAlias());
// Logs will include: {"timestamp":"...", "level":"INFO", "correlation_id":"abc123", 
//                     "operation":"create_url", "has_custom_alias":"true", ...}
```

**Alerting Strategy:**

| Alert | Threshold | Action | Severity |
| --- | --- | --- | --- |
| Feature flag status | Any change | Page on-call (track rollout) | INFO |
| Validation failure rate | >10% sustained | Investigate validator rules | WARNING |
| Alias conflicts | >1/min | Immediate investigation (UNIQUE constraint OK?) | CRITICAL |
| Rate limit hits | >5/min | Monitor for abuse pattern | WARNING |
| Custom alias error rate | >1% | Disable feature flag and investigate | CRITICAL |

---

## Optional (Post-MVP)

- Add reserved-word management from configuration instead of hardcoded list.
- Add alias ownership/edit/transfer capabilities for authenticated users.
- Add alias policy controls per tenant or per account tier.
- Add conflict analytics dashboards segmented by client/user cohort.

## Updated Acceptance Criteria

- ✅ Alias creation works when feature flag is enabled
- ✅ Disabled state returns `400 FEATURE_UNAVAILABLE` error
- ✅ Invalid alias (format/reserved/length) returns `400 INVALID_ALIAS`
- ✅ Duplicate alias returns `409 ALIAS_CONFLICT` deterministically
- ✅ Stricter rate limiting (2/min) enforced with proper 429 responses
- ✅ No backfill migration required; existing generated aliases remain valid
- ✅ Feature flag rollback preserves existing custom alias resolution
- ✅ Monitoring metrics collected (feature flag checks, validation failures, conflicts, rate limits)
- ✅ Alerts configured for anomalies (conflicts, validation failures, rate limit abuse)
