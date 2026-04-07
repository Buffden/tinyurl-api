# ADR-005: Technology Stack

**Status**: Accepted
**Date**: March 2026
**Deciders**: Architecture review

---

## Context

Single developer building a production-grade URL shortener. Stack must be practical to ship, not exhaustive. Every choice below is either **required to go live** or **explicitly deferred**.

---

## Decision

**Java 21 + Spring Boot 3.5 + Angular 19 + PostgreSQL 16**, containerised with Docker, deployed via GitHub Actions.

Redis, PgBouncer, and observability tooling are deferred to v2 where load data justifies them.

---

## Stack Summary

| Layer | Choice | Version | When |
| --- | --- | --- | --- |
| Language | Java | 21 LTS | v1 |
| Backend framework | Spring Boot | 3.5.x | v1 |
| Frontend framework | Angular | 19.x | v1 |
| Build tool | Gradle (Kotlin DSL) | 8.x | v1 |
| Database | PostgreSQL | 16 | v1 |
| JDBC connection pool | HikariCP | bundled | v1 |
| DB migrations | Flyway | 10.x | v1 |
| ORM | Spring Data JPA + Hibernate | bundled | v1 |
| API docs | SpringDoc OpenAPI 3 | 2.x | v1 |
| Validation | Jakarta Bean Validation | bundled | v1 |
| Rate limiting (infra) | Nginx (per-IP throttle) | — | v1 |
| Rate limiting (app) | Bucket4j + Caffeine (in-process token bucket) | 8.9.0 | v1 |
| Logging | SLF4J + Logback (JSON) | bundled | v1 |
| Health checks | Spring Boot Actuator | bundled | v1 |
| Unit tests | JUnit 5 + Mockito | bundled | v1 |
| Integration tests | Spring Boot Test + Testcontainers | — | v1 |
| Containerisation | Docker + Docker Compose | — | v1 |
| CI/CD | GitHub Actions + GHCR | — | v1 |
| Reverse proxy / TLS | Nginx + Let's Encrypt | — | v1 |
| Cache | Redis 7 + Lettuce | bundled | **v2** |
| External connection pooler | PgBouncer | 1.22.x | **v2** |
| Rate limiting (distributed) | Bucket4j + Redis (replaces in-process Caffeine) | 8.x | **v2** |
| Metrics & dashboards | Micrometer + Prometheus + Grafana | — | **v2** |
| Distributed tracing | OpenTelemetry | — | **v2** |
| Frontend UI component lib | Angular Material | 19.x | v1 |
| E2E tests | Cypress | — | **v2** |

> Items marked **bundled** require zero extra setup — Spring Boot pulls them in automatically.
> Items marked **v2** must not be added until v2 scope begins.
> Angular Material and Bucket4j (in-process) were added in v1 ahead of original ADR deferral — both justified by implementation reality without introducing new infrastructure dependencies.

---

## Rationale by Category

### Application Core

**Java 21 LTS**
The minimum version that ships virtual threads as a stable feature (`Thread.ofVirtual()`). For a redirect-heavy service, each request blocks briefly on a DB lookup — virtual threads let you write plain blocking code that scales to thousands of concurrent requests without async/reactive complexity. Using an LTS release means security patches for years without forced upgrades.

**Spring Boot 3.5**
Chosen because it bundles almost everything else in this stack (HikariCP, Hibernate, SLF4J, Actuator, validation, JUnit). For a single developer, the cost of wiring things manually is not worth it. Spring Boot's autoconfiguration handles PostgreSQL, connection pooling, logging format, and health endpoints with a few lines of `application.yml`.
Enable virtual threads with one property: `spring.threads.virtual.enabled=true`.

**Gradle (Kotlin DSL)**
Faster incremental builds than Maven for iterative development. The Kotlin DSL gives type-safe build scripts — autocomplete in the IDE catches build file errors before running. For a single developer who will touch the build file regularly (adding dependencies, configuring Flyway, setting up Testcontainers), this matters.

---

### Frontend

**Angular 19**
Angular's opinionated structure — components, services, dependency injection, built-in routing, HttpClient with interceptors — means the frontend architecture decisions are already made. For a project that will grow its UI over time, this constraint is a feature: there is one correct way to add a route, a service, or an HTTP call. The standalone component API removes the NgModule boilerplate that made earlier Angular versions feel heavyweight. TypeScript is enforced by default, which catches contract mismatches between frontend and backend at compile time.

**Angular Material 19**
Angular Material is included in v1. The component library provides the form and result UI without custom CSS overhead.

---

### Data

**PostgreSQL 16**
The primary data store. Schema and indexing strategy are documented in the database HLD. PostgreSQL was chosen in prior ADRs — this entry just records the version. v16 includes performance improvements to VACUUM and logical replication that are relevant if the cleanup job or v2 migration runs under load.

**HikariCP (bundled)**
Spring Boot's default JDBC connection pool. Zero configuration needed for v1 — default pool size of 10 connections handles the ~5 create QPS and DB-backed redirect lookups without saturation. Not an extra dependency; already present.

**Flyway 10**
Manages schema migrations as plain `.sql` files in `src/main/resources/db/migration/`. Runs automatically on app startup. Chosen over Liquibase because it uses raw SQL — no XML/YAML DSL to learn. Migration files are append-only once applied; never edit a version that has already run.

**Spring Data JPA + Hibernate (bundled)**
ORM layer for CRUD operations. The URL shortener schema is simple (one table in v1, one in v2) — JPA repositories cover all read/write operations without custom SQL. Not an extra dependency; bundled with Spring Boot.

---

### API

**SpringDoc OpenAPI 3**
Auto-generates the OpenAPI spec and a Swagger UI at `/swagger-ui.html` from Spring MVC annotations and Bean Validation constraints already written in the code. The API spec is a required LLD deliverable — SpringDoc produces it for free with no separate maintenance burden.
Disable in production: `springdoc.api-docs.enabled=false` in `application-prod.yml`.

**Jakarta Bean Validation (bundled)**
Annotation-driven request validation (`@NotNull`, `@Size`, `@URL`). Applied to request DTOs with `@Valid` in controllers. Spring Boot returns a structured 400 error automatically when validation fails. Not an extra dependency; bundled with Spring Boot.

---

### Security & Rate Limiting

**Nginx — per-IP rate limiting (v1)**
Coarse throttle per endpoint at the proxy layer before the request reaches the application. Three rate limit zones: `create_url` (40r/m), `redirect` (30r/m), `api_limit` (20r/s). Zero application code required — configured in `nginx.prod.conf`.

**Bucket4j + Caffeine — in-process rate limiting (v1)**
Application-layer per-IP token bucket (20 URL creations/hour) backed by a Caffeine in-process cache. Added as a defense-in-depth backstop: a misconfigured Nginx rule does not leave the application unprotected. Correct for a single EC2 instance — state is not shared across instances.

**Bucket4j + Redis — distributed rate limiting (v2)**
When autoscaling introduces multiple app instances, the in-process Caffeine backing store is replaced with Redis. Per-IP state becomes shared and atomic across all instances. The logic in `IpRateLimitFilter` does not change — only the backing store.

---

### Observability

**SLF4J + Logback (bundled)**
To be direct: SLF4J is not a library you install — it is already present in every Spring Boot application. It is the standard Java logging facade. Logback is its default implementation. The only deliberate choice here is outputting logs in **JSON format** (via `logstash-logback-encoder`) so log lines are machine-parseable from day one. This is one line in `logback-spring.xml`. Without structured logs, debugging in production means grepping unformatted text.

**Spring Boot Actuator (bundled)**
Exposes `GET /actuator/health` for Docker and CI health checks, and `GET /actuator/health/readiness` / `GET /actuator/health/liveness` for when Kubernetes is introduced. Not an extra dependency. Actuator is bundled and autoconfigured — the only required decision is which endpoints to expose and whether to secure them.

**Micrometer + Prometheus + Grafana — deferred to v2**
Spring Boot Actuator's health endpoint and JSON logs are sufficient to operate v1. A full metrics pipeline adds operational overhead that is not justified until v2 load targets require it.

**OpenTelemetry — deferred to v2**
OpenTelemetry adds trace IDs across the full request path (HTTP → service → DB → cache). In v1, structured JSON logs with correlation IDs achieve the same debugging goal at zero extra setup cost. OTel becomes worth the operational overhead when the DB and cache layers are separate infrastructure that needs to be traced independently — that is the v2 profile.

---

### Testing

**JUnit 5 + Mockito (bundled)**
Standard Java unit testing. Bundled with Spring Boot Test. Used for service-layer and utility logic. No external setup required.

**Spring Boot Test + Testcontainers**
Integration tests that spin up a real PostgreSQL Docker container for each test run. No mocks at the DB layer — this is deliberate. Mocking the database risks passing tests that fail against the real schema (constraint violations, index behaviour, migration-applied state). Testcontainers requires Docker running locally and on the CI runner — GitHub-hosted `ubuntu-latest` runners include Docker by default.

**Cypress — deferred to v2**
E2E browser tests for the Angular UI. v1 has two screens. Manual testing is sufficient. Cypress adds a non-trivial CI setup cost (browser installation, flake management) that is not justified until the UI grows.

---

### Containerisation

**Docker (multi-stage build)**
The backend is packaged as a Docker image using a multi-stage build: a `gradle build` stage produces the fat JAR, then an `eclipse-temurin:21-jre-alpine` runtime stage copies it in. This keeps the final image small (target: < 150 MB). Frontend build artifacts are deployed separately to CDN + object storage as defined in [ADR-006](ADR-006-frontend-hosting-cdn.md).

**Docker Compose**
Used for two purposes: (1) local development environment (PostgreSQL + app in one command), and (2) local integration test runs matching the CI environment. In v1, Docker Compose is also the production deployment model — one `docker-compose.yml` on the server.

---

### CI/CD

**GitHub Actions + GitHub Container Registry (GHCR)**
Pipeline-as-code in `.github/workflows/`. One workflow file in v1:

```text
push to main
  └── unit tests + Testcontainers integration tests
        └── Docker Compose smoke test (ephemeral credentials, health check, teardown)
              └── build Docker image
                    └── push to GHCR
                          └── cosign keyless image signing (Sigstore / OIDC)
                                └── deploy via SSM RunCommand (no SSH, no port 22)
```

Authentication to AWS uses OIDC — no long-lived access keys. The EC2 instance has an IAM role; deployment issues an SSM RunCommand to pull the new image and restart containers. All three stages must pass; smoke test failure blocks deploy.

GHCR is free for public repositories and natively integrated with GitHub Actions — no separate registry credentials to manage.

---

### Infrastructure

**Nginx + Let's Encrypt**
Nginx serves TLS termination and reverse proxy to the Spring Boot app. Let's Encrypt with Certbot provides free automated TLS certificate provisioning and renewal. No cost, no manual cert rotation.

Frontend static hosting strategy is recorded in [ADR-006](ADR-006-frontend-hosting-cdn.md): Angular SPA assets are served from CDN + object storage rather than from the backend Nginx container.

---

## Consequences

- `spring.threads.virtual.enabled=true` must be set explicitly in `application.yml`.
- Flyway migration files are append-only — never modify a version once it has run.
- Testcontainers requires Docker running locally and on CI (GitHub-hosted `ubuntu-latest` includes Docker by default).
- Angular `dist/` output is deployed to CDN + object storage as static assets (see [ADR-006](ADR-006-frontend-hosting-cdn.md)).
- SpringDoc must be disabled in production: `springdoc.api-docs.enabled=false` in `application-prod.yml`.
- CORS must be configured appropriately if frontend and API are served from different domains. If both are served under one domain with path-based routing (`/api/*` to backend), CORS can remain unnecessary.
