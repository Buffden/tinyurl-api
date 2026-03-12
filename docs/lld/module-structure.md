# Module Structure

> Java package layout, class responsibilities, dependency rules, and naming conventions for the URL Shortener application. This document is the written companion to the [C4 Level 4 — Code Diagram](c4-level4-code.excalidraw).

---

## Package Tree

```
com.tinyurl
├── controller          # HTTP layer — REST controllers and health
├── service             # Business logic — interfaces + implementations
├── dto                 # Data transfer objects — request/response/domain
├── repository          # Data access — JPA interfaces + implementations
├── cache               # Cache-aside client — interface + Redis impl (v2 only)
├── encoding            # Base62 encoder — interface + implementation
└── config              # Spring configuration — profiles, beans, properties
```

All packages live under the root `com.tinyurl`. No sub-packages within a package (flat structure per layer).

---

## Package Details

### 1. `com.tinyurl.controller`

**Spring stereotype:** `@RestController`, `@Component`

| Class | Stereotype | Responsibility |
|-------|-----------|----------------|
| `UrlController` | `@RestController` | Handles `POST /api/urls` — validates request body via `@Valid`, delegates to `UrlService.shortenUrl()`, returns `201 Created` with `CreateUrlResponse` |
| `RedirectController` | `@RestController` | Handles `GET /{short_code}` — extracts path variable, delegates to `UrlService.resolveCode()`, returns `301`/`302` redirect or `404`/`410` error |
| `HealthIndicator` | `@Component` | Implements Spring Boot `HealthIndicator` — pings the `DataSource`, returns `UP`/`DOWN` for `/actuator/health` |

**Dependencies:** `UrlService` (interface only — never the impl)

**Rules:**
- Controllers never import anything from `repository`, `cache`, or `encoding`.
- Controllers never contain business logic — they parse HTTP, delegate, and format responses.
- All validation annotations (`@Valid`, `@NotBlank`, `@Size`) live on DTO fields, not in controller methods.
- Error responses are produced by a `@RestControllerAdvice` exception handler, not inline in controllers.

---

### 2. `com.tinyurl.service`

**Spring stereotype:** `@Service`

| Class/Interface | Type | Responsibility |
|----------------|------|----------------|
| `UrlService` | `interface` | Defines the contract: `UrlMapping shortenUrl(CreateUrlRequest req)` and `Optional<UrlMapping> resolveCode(String code)` |
| `UrlServiceImpl` | `@Service` | Implements `UrlService`. Orchestrates the full create and redirect flows. Injects `UrlRepository`, `Base62Encoder`, and `CacheClient` (null/no-op in v1). |

**Create flow (`shortenUrl`):**
1. Validate and extract fields from `CreateUrlRequest`
2. Call `urlRepository.nextSequenceVal()` to get the next DB sequence ID
3. Call `base62Encoder.encode(id)` to produce the short code
4. Compute `expiresAt` from `expiresInDays` (default 180 per ADR-004)
5. Build `UrlMappingEntity`, call `urlRepository.save()`
6. Return `UrlMapping` domain object

**Resolve flow (`resolveCode`):**
1. *(v2)* Check `cacheClient.get(code)` — if cache hit, return immediately
2. Call `urlRepository.findByShortCode(code)` — if not found, return `Optional.empty()`
3. Check `is_deleted` → throw `GoneException`
4. Check `expires_at < now()` → throw `GoneException`
5. *(v2)* Call `cacheClient.set(code, mapping, ttl)` to populate cache
6. Return the `UrlMapping`

**Rules:**
- The service layer is the only place where business rules live (expiry checks, 301 vs 302 decision per ADR-003).
- Service classes never import Spring Web annotations (`@RequestBody`, `@PathVariable`, etc.).
- Service classes depend on interfaces (`UrlRepository`, `CacheClient`, `Base62Encoder`), never on implementations.

---

### 3. `com.tinyurl.dto`

**Spring stereotype:** None (plain Java classes / records)

| Class | Purpose | Fields |
|-------|---------|--------|
| `CreateUrlRequest` | Inbound POST body | `@NotBlank String url`, `@Positive Integer expiresInDays` (optional, default 180), `String alias` (v2, feature-flagged) |
| `CreateUrlResponse` | Outbound 201 body | `String shortUrl`, `String shortCode`, `String originalUrl`, `String expiresAt` (ISO 8601) |
| `ErrorResponse` | Outbound error body (all 4xx/5xx) | `String code` (machine-readable, UPPER_SNAKE_CASE), `String message` (human-readable) |
| `UrlMapping` | Internal domain object passed between service ↔ controller | `Long id`, `String shortCode`, `String originalUrl`, `LocalDateTime expiresAt`, `boolean deleted` |

**Rules:**
- DTOs are immutable — use Java `record` types where possible.
- Validation annotations (`@NotBlank`, `@Size`, `@Positive`) go on `CreateUrlRequest` fields only.
- `UrlMapping` is a domain transfer object — **not** the JPA entity. Mapping between `UrlMappingEntity` and `UrlMapping` happens in the repository layer.
- No business logic in DTOs.

---

### 4. `com.tinyurl.repository`

**Spring stereotype:** `@Repository`

| Class/Interface | Type | Responsibility |
|----------------|------|----------------|
| `UrlRepository` | `interface` (extends `JpaRepository<UrlMappingEntity, Long>`) | Data access contract: `Long nextSequenceVal()`, `UrlMappingEntity save(UrlMappingEntity)`, `Optional<UrlMappingEntity> findByShortCode(String code)` |
| `JpaUrlRepository` | `@Repository` | Implements `UrlRepository` using `EntityManager`. Executes raw SQL for sequence (`SELECT nextval('url_seq')`), standard JPA for save/find. Maps `UrlMappingEntity` ↔ DB rows. |
| `UrlMappingEntity` | `@Entity` | JPA entity mapped to `url_mappings` table. Fields: `Long id`, `String shortCode`, `String originalUrl`, `OffsetDateTime createdAt`, `OffsetDateTime expiresAt`, *(v2)* `boolean isDeleted`, `OffsetDateTime deletedAt`, `String urlHash`, `String createdIp`, `String userAgent` |

**Rules:**
- The entity class (`UrlMappingEntity`) lives in the `repository` package — it is an implementation detail, not shared.
- All DB-specific concerns (SQL, JPA annotations, entity mapping) are confined to this package.
- The repository returns `UrlMappingEntity` to the service, which converts it to `UrlMapping` (the domain DTO).
- Timestamps use `OffsetDateTime` (maps to `TIMESTAMPTZ`) — never `LocalDateTime` for DB-bound fields.

---

### 5. `com.tinyurl.cache` *(v2 only)*

**Spring stereotype:** `@Component`

| Class/Interface | Type | Responsibility |
|----------------|------|----------------|
| `CacheClient` | `interface` | Cache-aside contract: `Optional<UrlMapping> get(String code)`, `void set(String code, UrlMapping mapping, Duration ttl)`, `void setNegative(String code, Duration ttl)` |
| `RedisCacheClient` | `@Component` | Implements `CacheClient` using `RedisTemplate<String, String>`. Key prefix: `url:`. JSON marshal/unmarshal for `UrlMapping`. Circuit-breaker wraps all Redis calls — cache failure must not break the redirect path. |

**Rules:**
- In v1, `CacheClient` is **not injected** — the service skips cache calls entirely (no no-op implementation needed; the service checks for null or uses a `@ConditionalOnProperty` flag).
- Cache never writes to the DB — it is read-through and populate-on-miss only.
- Negative caching: `setNegative()` stores a tombstone for short codes that don't exist, preventing repeated DB misses.
- TTL values are externalized to `application.yml`, not hardcoded.

---

### 6. `com.tinyurl.encoding`

**Spring stereotype:** `@Component`

| Class/Interface | Type | Responsibility |
|----------------|------|----------------|
| `Base62Encoder` | `interface` | Encoding contract: `String encode(long id)`, `long decode(String code)`. Charset: `0-9 A-Z a-z` (62 characters). |
| `Base62EncoderImpl` | `@Component` | Implements `Base62Encoder`. Pure function — no external dependencies, stateless, thread-safe. Output padded to minimum 4 characters. |

**Rules:**
- This package has **zero dependencies** on any other `com.tinyurl` package.
- The encoder is a pure mathematical function — no I/O, no Spring context needed for unit testing.
- The charset order (`0-9 A-Z a-z`) is fixed and must never change (existing short codes would break).

---

### 7. `com.tinyurl.config`

**Spring stereotype:** `@Configuration`, `@ConfigurationProperties`

| Class | Responsibility |
|-------|----------------|
| `AppProperties` | `@ConfigurationProperties("tinyurl")` — binds `tinyurl.base-url`, `tinyurl.default-expiry-days`, `tinyurl.short-code-min-length`, feature flags |
| `WebConfig` | `@Configuration` — registers middleware filters (Logging, Metrics, Recovery); configures CORS |
| `DataSourceConfig` | `@Configuration` — HikariCP pool settings (sourced from `application.yml`; secrets from AWS SSM in prod) |
| `RedisConfig` | `@Configuration` `@Profile("v2")` — Lettuce connection factory, `RedisTemplate` bean, circuit-breaker config |
| `SecurityConfig` | `@Configuration` — *(v2)* authentication for admin endpoints |

**Rules:**
- All magic numbers (default expiry, min code length, cache TTL, pool sizes) are defined in `application.yml` and bound via `@ConfigurationProperties`.
- v2-only beans are gated with `@Profile("v2")` or `@ConditionalOnProperty`.
- No business logic in configuration classes.

---

## Dependency Rules

The dependency graph is strictly layered. Violations are caught by ArchUnit tests.

```
controller ──→ service ──→ repository
                  │    ──→ cache (v2)
                  │    ──→ encoding
                  │
controller ──→ dto ←── service ←── repository (entity → dto mapping)
```

### Allowed

| From | May depend on |
|------|---------------|
| `controller` | `service` (interfaces only), `dto` |
| `service` | `repository` (interfaces only), `cache` (interfaces only), `encoding` (interfaces only), `dto` |
| `repository` | `dto` (for entity ↔ domain mapping) |
| `cache` | `dto` (for serialization) |
| `encoding` | *nothing* |
| `config` | All packages (wiring beans) |

### Forbidden

| From | Must NOT depend on |
|------|-------------------|
| `controller` | `repository`, `cache`, `encoding` |
| `service` | Any implementation class (only interfaces) |
| `repository` | `controller`, `service`, `cache`, `encoding` |
| `cache` | `controller`, `service`, `repository`, `encoding` |
| `encoding` | Any other `com.tinyurl` package |
| Any package | `config` (config is a dependency root, not a dependency target) |

---

## Middleware Chain

From the [C4 Level 3 — Component Diagram](c4-level3-component.excalidraw). Middleware is implemented as Spring `Filter` beans registered in `WebConfig`.

| Order | Filter | Responsibility |
|-------|--------|----------------|
| 1 | `LoggingFilter` | Generates `X-Correlation-ID` (UUID), attaches to MDC, logs request/response metadata via SLF4J |
| 2 | `MetricsFilter` | Records Prometheus counters and latency histograms per endpoint via Micrometer |
| 3 | `RecoveryFilter` | Catches unhandled exceptions, logs stack trace, returns `500 INTERNAL_ERROR` |
| 4 | `RateLimitFilter` *(v2)* | Bucket4j token-bucket per client IP; returns `429 RATE_LIMIT_EXCEEDED` with `Retry-After` header |

Filters live in a `com.tinyurl.filter` sub-package or directly in `config` — they are infrastructure, not domain.

---

## Exception Handling

A single `@RestControllerAdvice` class (`GlobalExceptionHandler`) maps exceptions to error responses:

| Exception | HTTP Status | Error Code |
|-----------|-------------|------------|
| `MethodArgumentNotValidException` | 400 | `INVALID_URL` or `INVALID_EXPIRY` (from validation annotations) |
| `NotFoundException` | 404 | `NOT_FOUND` |
| `GoneException` | 410 | `GONE` |
| `RateLimitExceededException` *(v2)* | 429 | `RATE_LIMIT_EXCEEDED` |
| `AliasConflictException` *(v2)* | 409 | `ALIAS_TAKEN` |
| `Exception` (catch-all) | 500 | `INTERNAL_ERROR` |

Custom exceptions (`NotFoundException`, `GoneException`, etc.) live in a `com.tinyurl.exception` package.

---

## Spring Profiles

| Profile | Active in | Effect |
|---------|-----------|--------|
| `default` | Local dev, CI | In-memory H2 (tests), Docker PostgreSQL (dev); no Redis, no SSM |
| `v2` | v2 deployment | Enables Redis beans, `RateLimitFilter`, cache-aside in service |
| `prod` | Production | Activates AWS SSM property source (`spring.config.import: aws-parameterstore:/tinyurl/prod/`), sets production pool sizes and logging levels |

---

## Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Package | Lowercase, singular | `com.tinyurl.service` (not `services`) |
| Interface | Noun or noun phrase, no `I` prefix | `UrlService`, `CacheClient`, `Base62Encoder` |
| Implementation | Interface name + `Impl` or tech qualifier | `UrlServiceImpl`, `JpaUrlRepository`, `RedisCacheClient`, `Base62EncoderImpl` |
| Controller | Resource name + `Controller` | `UrlController`, `RedirectController` |
| DTO | Purpose + suffix (`Request`, `Response`) or domain name | `CreateUrlRequest`, `CreateUrlResponse`, `ErrorResponse`, `UrlMapping` |
| Entity | Domain name + `Entity` | `UrlMappingEntity` |
| Filter | Purpose + `Filter` | `LoggingFilter`, `MetricsFilter`, `RateLimitFilter` |
| Exception | Condition + `Exception` | `NotFoundException`, `GoneException` |
| Config | Scope + `Config` or `Properties` | `WebConfig`, `AppProperties`, `RedisConfig` |
| Test | Class under test + `Test` / `IntegrationTest` | `UrlServiceImplTest`, `UrlControllerIntegrationTest` |
