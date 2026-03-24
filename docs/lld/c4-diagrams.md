# C4 Model — TinyURL

> Five diagrams that progressively zoom into the TinyURL system — from its external environment, through deployable containers and internal components, down to Java code contracts, and finally the Docker Compose deployment topology with AWS secrets management.
>
> Each diagram is an `.excalidraw` file in this folder. Open them in VS Code with the [Excalidraw extension](https://marketplace.visualstudio.com/items?itemName=pomdtr.excalidraw-editor).

---

## Diagram Index

| # | Level | File | Scope |
|---|-------|------|-------|
| 1 | System Context | [c4-level1-system-context.excalidraw](c4-level1-system-context.excalidraw) | TinyURL as a black box — external actors (End User) and edge/origin systems (CloudFront, S3) |
| 2 | Container | [c4-level2-container.excalidraw](c4-level2-container.excalidraw) | All deployable/runtime units — CloudFront CDN, S3 SPA, Nginx, URL Shortener App, Redis, PgBouncer, PostgreSQL, Observability |
| 3 | Component | [c4-level3-component.excalidraw](c4-level3-component.excalidraw) | Internals of the Spring Boot application — DispatcherServlet, middleware chain, controllers, services, repositories |
| 4 | Code | [c4-level4-code.excalidraw](c4-level4-code.excalidraw) | Java interfaces, classes, fields, methods, and the full dependency graph across 6 packages |
| 5 | Deployment | [c4-deployment.excalidraw](c4-deployment.excalidraw) | Docker Compose topology on EC2 — images, ports, volumes, health checks, and AWS SSM secrets flow |

---

## Visual Conventions

### Color Scheme

All five diagrams share a consistent palette so elements are instantly recognizable across zoom levels:

| Color | Stroke | Background | Represents | Example |
|-------|--------|------------|------------|---------|
| Green | `#2f9e44` | `#d3f9d8` | Entry / Proxy layer | Nginx |
| Orange | `#e67700` | `#fff9db` | Application / Cache tier | URL Shortener App, Redis, UrlService |
| Red | `#c92a2a` | `#ffe3e3` | Data tier | PostgreSQL, PgBouncer, UrlRepository |
| Blue | `#1971c2` | `#dbe4ff` | System boundary / Router / Handlers | TinyURL System box, DispatcherServlet, Controllers |
| Grey | `#868e96` | `#dee2e6` | External systems / DTOs / Utilities / Observability | DNS, Prometheus, Base62Encoder, ErrorResponse |
| Purple | `#6741d9` | `#e5dbff` | AWS Managed Services | CloudFront, S3 Bucket, SSM Parameter Store |

### Arrow Conventions

| Style | Color | Meaning |
|-------|-------|---------|
| Solid | Blue | Read path (redirect flow) |
| Solid | Red | Write path (create flow, DB writes) |
| Dashed | Grey | Observability / ops (metrics, logs, health) |
| Dashed | Orange | Cache operations — **v2 only** |
| Dashed | Purple | Secrets fetch (SSM) |

### Font

All text uses **Excalifont** (`fontFamily: 5`) across every diagram for consistency.

### v1 vs v2 Markers

Components that only exist in v2 are explicitly annotated with **(v2)** or **(v2 only)** in both the diagrams and this document. Removing all v2-marked items leaves a fully functional v1 system.

---

## Level 1 — System Context

**Scope:** The entire TinyURL system as a single box, surrounded by its actors and external dependencies.

**Question answered:** *What is TinyURL and who/what interacts with it?*

### Elements

| Element | Shape | Color | Description |
|---------|-------|-------|-------------|
| End User | Person (grey ellipse) | Grey | Creates short URLs and accesses redirects via browser |
| TinyURL System | Software System (blue rect) | Blue | Accepts long URLs, generates unique short codes, and redirects users to original URLs with low latency (<100 ms P95) |
| CloudFront CDN | External System (purple rect) | Purple | Edge entry for both API and redirect traffic; routes SPA and API paths to correct origins |
| S3 Bucket | External System (green rect) | Green | Stores built Angular SPA static assets as origin content |

### Interactions (4 arrows)

| From → To | Label | Style |
|-----------|-------|-------|
| End User → CloudFront CDN | `HTTPS requests` | Solid |
| CloudFront CDN → TinyURL System | `API calls + redirect requests` | Solid |
| CloudFront CDN → S3 Bucket | `Origin fetch (SPA assets)` | Solid |
| TinyURL System → End User | `301 Redirect` | Solid |

### Key Takeaways

- TinyURL remains a **stateless web service** exposed to the public internet.
- Public traffic enters through **CloudFront**, not directly to backend containers.
- SPA assets are served from **S3 through CloudFront**; API and redirect traffic is routed to backend.
- No database, cache, or host-level deployment details are visible — that's the point of L1.

---

## Level 2 — Container

**Scope:** Zooms inside the system boundary to show every deployable container (process) and how they communicate.

**Question answered:** *What are the major technical building blocks and how do they talk to each other?*

### Tier Layout

| Tier | Colour | Containers |
|------|--------|------------|
| **Public Internet** | Grey | User, DNS Route53 (`tinyurl.buffden.com`) |
| **CDN / Edge Layer** | Purple | CloudFront CDN |
| **Frontend Origin** | Green | S3 Angular SPA static assets / UI |
| **Entry Layer** | Green | Nginx (TLS termination + reverse proxy) |
| **Application Tier** | Orange | URL Shortener App (stateless instances + token-bucket rate limiting) |
| **Cache Layer** | Orange | Redis (cache-aside + negative caching) — **v2 only** |
| **Data Tier** | Red | PgBouncer (transaction pooling, connection-exhaustion guard), PostgreSQL Primary DB (`url_mappings` table, sequence-based ID, WAL archiving) |
| **Observability** | Grey | Metrics (P95, P99, QPS via Prometheus), Structured Logs |

### Interactions (22 arrows)

**Write Path (Create URL):**

| From → To | Label |
|-----------|-------|
| User → DNS Route53 | `Resolve DNS` |
| User → CloudFront CDN | `HTTPS requests` |
| CloudFront CDN → Nginx | `/api/*` |
| Nginx → URL Shortener App | Write |
| URL Shortener App → PgBouncer → PostgreSQL | `INSERT mapping` |
| PostgreSQL → URL Shortener App | `Confirm Write` |
| URL Shortener App → User | `Return Short URL` |

**Read Path (Redirect):**

| From → To | Label |
|-----------|-------|
| User → DNS Route53 | `Resolve DNS` |
| User → CloudFront CDN | `HTTPS requests` |
| CloudFront CDN → Nginx | `/{short_code}` |
| Nginx → URL Shortener App | Read |
| URL Shortener App → Redis | `Lookup short_code` |
| Redis → URL Shortener App | `Cache hit` or `NULL (negative cache)` |
| URL Shortener App → PgBouncer → PostgreSQL | `Cache Miss Lookup` (on miss) |
| PostgreSQL → URL Shortener App | `Return Mapping` |
| URL Shortener App → Redis | `Cache populate (TTL)` or `stores NULL` |
| URL Shortener App → User | `HTTP 301/302 Redirect` or `HTTP 404` |

**SPA Path (Frontend):**

| From → To | Label |
|-----------|-------|
| User → DNS Route53 | `Resolve DNS` |
| User → CloudFront CDN | `HTTPS requests` |
| CloudFront CDN → S3 Angular SPA | `/*` |

**Observability:**

| From → To | Label |
|-----------|-------|
| URL Shortener App → Structured Logs | `Structured Logs` |
| URL Shortener App → Metrics | `Emit Latency/RPS` |

### Key Takeaways

- The app is **stateless** — horizontal scaling is trivial (add more instances behind Nginx origin).
- CloudFront is the first hop for both API and redirect traffic; S3 serves SPA assets via CDN.
- **PgBouncer** sits between the app and PostgreSQL to cap connections under spike traffic.
- **Redis is only present in v2**; v1 goes straight from app to DB on every read.
- Observability is a sidecar concern — its failure **does not** affect the write/read path.
- The diagram explicitly separates **write path** and **read path** arrows for clarity.

---

## Level 3 — Component

**Scope:** Zooms inside the **URL Shortener App** container to reveal its internal components — the Spring Boot middleware chain, controllers, services, and data-access components.

**Question answered:** *What are the major structural parts inside the Spring Boot application?*

### Middleware Chain (grey zone)

Requests flow through these filters in order before reaching a controller:

| # | Component | Purpose |
|---|-----------|---------|
| 1 | Logging MW | Injects correlation ID, emits structured logs via `SLF4J + Logback` |
| 2 | Metrics MW | Increments Prometheus counters and histograms per route |
| 3 | Recovery MW | Catches unhandled exceptions, returns 500 instead of crashing |
| 4 | Rate Limit MW *(v2)* | Token-bucket per-IP rate limiter (Bucket4j + Redis) |
| 5 | Feature Flag Guard *(v2)* | Toggles custom-alias creation on/off at runtime |

### DispatcherServlet + Filter Chain (blue)

Central Spring MVC router — dispatches incoming HTTP requests to the correct handler after passing through the middleware chain.

### Controllers (blue)

| Controller | Route | Behaviour |
|------------|-------|-----------|
| **Create Handler** | `POST /api/urls` | Validates input → calls `shortenUrl()` → returns `201 Created` + JSON |
| **Redirect Handler** | `GET /{code}` | Extracts `{code}` → calls `resolveCode()` → returns `301`/`302`/`404`/`410` |
| **Health Handler** | `GET /actuator/health` | Spring Boot Actuator — liveness + readiness probe, pings DB → `{"status":"UP"}` or `503` |

### Business Logic (orange)

| Component | Role |
|-----------|------|
| **URL Service** | Orchestrates the full shorten/resolve flow — expiry checks, cache-aside pattern, Base62 encoding, repository calls |
| **Base62 Encoder** | Pure utility — converts a sequence `long` to a short-code string using charset `0-9 A-Z a-z` |

### Data Access (red)

| Component | Role |
|-----------|------|
| **URL Repository** | Spring Data JPA queries — `save()`, `findByShortCode()`, `nextval()` for sequence-based ID generation |
| **Cache Client** *(v2)* | Lettuce `GET`/`SET` with TTL, negative-caching for misses (prevents cache stampede), circuit breaker on Redis failure |

### Config & Externals

| Component | Role |
|-----------|------|
| **Config** | Environment-based configuration — DB, Redis, app settings |
| **PostgreSQL 16** | External container (accessed via PgBouncer) |
| **Redis 7** *(v2)* | External container (cache cluster) |

### Component Interactions (11 arrows)

| From → To | Label | Style |
|-----------|-------|-------|
| Nginx → DispatcherServlet | `HTTP/1.1` | Blue solid |
| DispatcherServlet → Create Handler | `POST` | Red solid |
| DispatcherServlet → Redirect Handler | `GET` | Blue solid |
| DispatcherServlet → Health Handler | — | Grey dashed |
| Create Handler → URL Service | `shortenUrl()` | Red solid |
| Redirect Handler → URL Service | `resolveCode()` | Blue solid |
| URL Service → Base62 Encoder | `encode()` | Grey solid |
| URL Service → URL Repository | `SQL queries` | Red solid |
| URL Service → Cache Client *(v2)* | `GET/SET (v2)` | Orange dashed |
| URL Repository → PostgreSQL 16 | `JPA + SQL` | Red solid |
| Cache Client → Redis 7 *(v2)* | `Lettuce` | Orange solid |

### Key Takeaways

- **Controllers are thin** — they parse HTTP, validate with `@Valid`, and delegate to the service layer.
- The **service layer owns all business rules** (expiry, cache-aside, encoding).
- Repository and cache are **behind interfaces**, making them independently testable and swappable.
- v2 components are clearly marked — removing them leaves a fully functional v1 app.
- The middleware chain runs **before** dispatch, providing cross-cutting concerns (logging, metrics, rate limiting) without polluting business logic.

---

## Level 4 — Code

**Scope:** Java interfaces, classes, fields, and methods — the actual code contracts before writing implementation files. Organized into 6 packages.

**Question answered:** *What are the exact classes, interfaces, and their relationships?*

### `com.tinyurl.controller` (blue zone)

| Type | Fields | Methods |
|------|--------|---------|
| `@RestController UrlController` | `urlService: UrlService` | `createUrl(@Valid @RequestBody req)` — parse JSON body → call `urlService.shortenUrl()` → write `201` + JSON response |
| `@RestController RedirectController` | `urlService: UrlService` | `redirect(@PathVariable code)` — extract `{code}` from path → call `urlService.resolveCode()` → `301`/`302` or `404`/`410` |
| `@Component HealthIndicator` | `dataSource: DataSource` | `health()` — ping DB → return `{"status":"UP"}` → `200` or `503` |

> **Note:** `HealthIndicator` has no explicit arrows in the diagram — its `DataSource` dependency is Spring-managed infrastructure, not an application-defined class.

### `com.tinyurl.service` (orange zone)

| Type | Signature |
|------|-----------|
| `interface UrlService` | `UrlMapping shortenUrl(CreateUrlRequest req)` |
| | `Optional<UrlMapping> resolveCode(String code)` |
| `@Service UrlServiceImpl` | Fields: `urlRepository: UrlRepository`, `cacheClient: CacheClient` (null in v1), `base62Encoder: Base62Encoder` |
| | Implements `UrlService` |

### `com.tinyurl.dto` (grey zone — DTOs)

| Class | Fields |
|-------|--------|
| `CreateUrlRequest` | `String url`, `int expiresInDays`, `String alias` *(v2)* |
| `UrlMapping` | `Long id`, `String shortCode`, `String originalUrl`, `LocalDateTime expiresAt`, `boolean deleted` |
| `ErrorResponse` | `String code`, `String message` |
| `CreateUrlResponse` | `String shortUrl`, `String shortCode`, `String originalUrl`, `String expiresAt` |

### `com.tinyurl.repository` (red zone)

| Type | Details |
|------|---------|
| `interface UrlRepository extends JpaRepository<UrlMappingEntity, Long>` | `Long nextSequenceVal()`, `UrlMappingEntity save(UrlMappingEntity entity)`, `Optional<UrlMappingEntity> findByShortCode(String code)` |
| `@Repository JpaUrlRepository` | `entityManager: EntityManager` — implements `UrlRepository`. SQL: `SELECT nextval('url_seq')`, `INSERT INTO urls (…)`, `SELECT * FROM urls WHERE short_code = ?1` |

### `com.tinyurl.cache` (orange zone — v2 only)

| Type | Details |
|------|---------|
| `interface CacheClient` | `Optional<UrlMapping> get(String code)`, `void set(String code, UrlMapping mapping, Duration ttl)`, `void setNegative(String code, Duration ttl)` |
| `@Component RedisCacheClient` | `redisTemplate: RedisTemplate<String, String>`, `prefix: "url:"` — implements `CacheClient`. JSON marshal/unmarshal for mapping objects, circuit breaker wraps Redis calls |

### `com.tinyurl.encoding` (grey zone)

| Type | Details |
|------|---------|
| `interface Base62Encoder` | `String encode(long id)`, `long decode(String code)` — charset `0-9 A-Z a-z` |
| `@Component Base62EncoderImpl` | Implements `Base62Encoder` — stateless, thread-safe, pure function, padded to min 4 chars |

### Dependency Graph (10 arrows)

| From → To | Relationship | Style |
|-----------|-------------|-------|
| `UrlController` → `UrlService` | uses | Solid |
| `RedirectController` → `UrlService` | uses | Solid |
| `UrlServiceImpl` → `UrlService` | **implements** ▷ | Solid (open arrowhead) |
| `UrlServiceImpl` → `UrlRepository` | depends on | Solid |
| `UrlServiceImpl` → `dto` (package) | uses | Solid |
| `UrlServiceImpl` → `CacheClient` | depends on *(v2)* | Dashed |
| `UrlServiceImpl` → `Base62Encoder` | depends on | Solid |
| `JpaUrlRepository` → `UrlRepository` | **implements** ▷ | Solid (open arrowhead) |
| `Base62EncoderImpl` → `Base62Encoder` | **implements** ▷ | Solid (open arrowhead) |
| `RedisCacheClient` → `CacheClient` | **implements** ▷ | Solid (open arrowhead) |

### Key Takeaways

- **Every cross-layer dependency is mediated by an interface** → easy to mock in unit tests with Mockito.
- Controllers depend **only** on `UrlService`; they never touch the DB or cache directly.
- `CacheClient` is **null-safe in v1** — `UrlServiceImpl` skips cache calls when the field is null.
- DTOs (`dto` package) carry **no behaviour** — pure data transfer objects with Jakarta Bean Validation annotations.
- There are **4 implements relationships** (open arrowhead ▷ ) ensuring every concrete class can be swapped.
- The `UrlServiceImpl → dto` arrow represents import-level usage (constructing request/response objects).

---

## Level 5 — Deployment (Docker Compose + AWS)

**Scope:** Maps every container to its Docker Compose service on an EC2 instance, showing images, ports, volumes, health checks, inter-service dependencies, and AWS SSM secrets management.

**Question answered:** *How is this system physically deployed and where do secrets come from?*

### Infrastructure Node

```
EC2 Instance (Linux VM)
└── docker-compose.yml
    ├── Network: tinyurl-net (bridge — all containers)
    └── Named Volumes: pg-data, redis-data (v2)
```

### Services (7 Docker Compose containers)

| Service | Image | Ports | Key Config | Health Check |
|---------|-------|-------|------------|--------------|
| **nginx** | `nginx:1.25-alpine` | `80:80`, `443:443` (host-mapped) | Volumes: `./nginx/nginx.conf`, `./certs/` (TLS) | `curl -f http://localhost/actuator/health` |
| **app** | `tinyurl-app:latest` | `8080` (internal only) | Gradle multi-stage build → `eclipse-temurin:21-jre-alpine` runtime. Env: `BASE_URL`, `LOG_LEVEL`. Secrets from SSM. Depends on: pgbouncer (healthy), redis (healthy, v2) | `GET http://localhost:8080/actuator/health` |
| **prometheus** | `prom/prometheus:latest` | `9090:9090` | Volume: `./prometheus.yml`. Scrape target: `app:8080/metrics`. Retention: 15 d | — |
| **pgbouncer** | `edoburu/pgbouncer:latest` | `6432` (internal) | Transaction pooling, `max_client_conn=100`, `pool_size=20`. Depends on: postgres (healthy) | — |
| **postgres** | `postgres:16-alpine` | `5432` (internal, `5433:5432` for dev) | Volume: `pg-data:/var/lib/postgresql/data`, `./migrations/`. Env: `POSTGRES_DB=tinyurl`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `pg_isready -U tinyurl` |
| **redis** *(v2)* | `redis:7-alpine` | `6379` (internal) | Volume: `redis-data:/data` (AOF persistence) | `redis-cli ping` |
| **flyway** *(run-once)* | `flyway/flyway:10-alpine` | — | Volume: `./migrations/ → /flyway/sql`. Command: `-url jdbc:postgresql://postgres:5432/tinyurl migrate`. Depends on: postgres (healthy). Restart: on-failure | — |

### AWS Managed Services

| Service | Details |
|---------|---------|
| **SSM Parameter Store** | SecureString parameters under `/tinyurl/prod/*`, KMS-encrypted. Stores: `spring.datasource.password`, `spring.redis.password` |

The app fetches secrets at startup via `GetParametersByPath` using the EC2 instance's **IAM Role** — no credentials stored in code, env vars, or Docker Compose files.

### Secrets Management Flow

```
┌──────────────────────┐     GetParametersByPath      ┌──────────────────────────────┐
│   app container      │─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─▶│  AWS SSM Parameter Store     │
│   (Java 21 +         │       (IAM Role)              │  /tinyurl/prod/*             │
│    Spring Boot 3)    │◀─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│  SecureString + KMS          │
│                      │  spring.datasource.password   │  spring.datasource.password  │
│                      │  spring.redis.password         │  spring.redis.password       │
└──────────────────────┘                               └──────────────────────────────┘
```

**Spring Boot integration:** `spring-cloud-aws-starter-parameter-store` auto-resolves properties from SSM at boot time through `spring.config.import: aws-parameterstore:/tinyurl/prod/`.

### Network Topology (11 arrows)

```
Internet
  │
  │ HTTPS — tinyurl.buffden.com / app.tinyurl.buffden.com
  ▼
CloudFront (API + SPA distributions)
  │
  ├── API dist ── proxy :443 ──▶ nginx
  └── SPA dist ── OAC fetch ───▶ S3 Bucket
  
┌──────────────────────────────────────────────────────────────────────────────┐
│  Docker Host (EC2)                                                           │
│                                                                              │
│  ┌─────────────┐                                                             │
│  │   nginx     │──── proxy_pass :8080 ───▶┌─────────────┐                    │
│  │  :80, :443  │                          │    app      │─ ─ ─▶ AWS SSM     │
│  └─────────────┘                          │   :8080     │   (IAM Role)       │
│                                           └──────┬──────┘                    │
│                 scrape /metrics ◀───── prometheus :9090                       │
│                                           │           │                      │
│                         ┌─────────────────┘           │                      │
│                  HikariCP :6432              Lettuce :6379 (v2)               │
│                         ▼                             ▼                      │
│                  ┌────────────┐              ┌─────────────┐                 │
│                  │ pgbouncer  │              │    redis    │                 │
│                  │   :6432    │              │    :6379    │                 │
│                  └─────┬──────┘              └─────────────┘                 │
│               TCP :5432│                                                     │
│                        ▼              DDL :5432                               │
│                  ┌────────────┐◀──────────────┌────────────┐                 │
│                  │ postgresql │               │   flyway   │                 │
│                  │   :5432    │               │  (run-once) │                 │
│                  └────────────┘               └────────────┘                 │
│                                                                              │
│  Volumes: pg-data (WAL + data), redis-data (AOF, v2)                         │
│  Network: tinyurl-net (bridge, all containers)                               │
└──────────────────────────────────────────────────────────────────────────────┘
```

| From → To | Arrow Label | Style |
|-----------|-------------|-------|
| Internet → CloudFront Distribution (API) | `HTTPS — tinyurl.buffden.com` | Blue solid |
| CloudFront Distribution (API) → nginx | `proxy :443` | Blue solid |
| nginx → app | `proxy_pass :8080` | Blue solid |
| app → pgbouncer | `HikariCP → :6432` | Red solid |
| app → redis *(v2)* | `Lettuce :6379 (v2)` | Orange dashed |
| pgbouncer → postgres | `TCP :5432` | Red solid |
| prometheus → app | `scrape /metrics` | Grey dashed |
| flyway → postgres | `DDL :5432` | Grey dashed |
| app → SSM Parameter Store | `GetParametersByPath (IAM Role)` | Purple dashed |
| Internet → CloudFront Distribution (SPA) | `HTTPS — app.tinyurl.buffden.com` | Blue solid |
| CloudFront Distribution (SPA) → S3 Bucket | `OAC fetch` | Purple dashed |

### Key Takeaways

- Public entry is **CloudFront-first** (ADR-006): API distribution forwards to Nginx; SPA distribution fetches from S3 via OAC.
- Only **nginx** exposes ports to the host (`80`, `443`). All other runtime services communicate on the internal `tinyurl-net` bridge network.
- **PgBouncer** prevents connection exhaustion — the app connects via HikariCP to PgBouncer `:6432`, never directly to PostgreSQL.
- **Flyway** runs once on startup (`restart: on-failure`) and applies DDL migrations via JDBC.
- **Redis** is only present in v2 — the compose file can omit it entirely for v1.
- Named volumes (`pg-data`, `redis-data`) ensure data survives container restarts.
- **No secrets in env vars or code** — the app uses its EC2 IAM Role to fetch `SecureString` parameters from AWS SSM Parameter Store at boot time, with KMS encryption at rest.
- The Docker image uses a **Gradle multi-stage build** with `eclipse-temurin:21-jre-alpine` as the runtime base (minimal attack surface, ~80 MB).

---

## Cross-References

| Topic | Document |
|-------|----------|
| API Contract (endpoints, error codes) | [api-contract.md](api-contract.md) |
| Database Schema & Operations | [../hld/database/](../hld/database/) |
| System Design (HLD) | [../hld/system-design/system-design.md](../hld/system-design/system-design.md) |
| Architecture Decision Records | [../architecture/00-baseline/adr/](../architecture/00-baseline/adr/) |
| Non-Functional Requirements | [../requirements/non-functional-requirements.md](../requirements/non-functional-requirements.md) |
| Functional Requirements | [../requirements/functional-requirements.md](../requirements/functional-requirements.md) |
