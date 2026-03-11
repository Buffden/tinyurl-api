# ADR-005: Technology Stack

**Status**: Accepted
**Date**: March 2026
**Deciders**: Architecture review

---

## Context

Single developer building a production-grade URL shortener. Stack must be practical to ship, not exhaustive. Every choice below is either **required to go live** or **explicitly deferred**.

---

## Decision

**Java 21 + Spring Boot 3 + Angular 18 + PostgreSQL 16**, containerised with Docker, deployed via GitHub Actions.

Redis, PgBouncer, and observability tooling are deferred to v2 where load data justifies them.

---

## Stack Summary

| Layer | Choice | Version | When |
| --- | --- | --- | --- |
| Language | Java | 21 LTS | v1 |
| Backend framework | Spring Boot | 3.3.x | v1 |
| Frontend framework | Angular | 18.x | v1 |
| Build tool | Gradle (Kotlin DSL) | 8.x | v1 |
| Database | PostgreSQL | 16 | v1 |
| JDBC connection pool | HikariCP | bundled | v1 |
| DB migrations | Flyway | 10.x | v1 |
| ORM | Spring Data JPA + Hibernate | bundled | v1 |
| API docs | SpringDoc OpenAPI 3 | 2.x | v1 |
| Validation | Jakarta Bean Validation | bundled | v1 |
| Rate limiting | Nginx (per-IP throttle) | — | v1 |
| Logging | SLF4J + Logback (JSON) | bundled | v1 |
| Health checks | Spring Boot Actuator | bundled | v1 |
| Unit tests | JUnit 5 + Mockito | bundled | v1 |
| Integration tests | Spring Boot Test + Testcontainers | — | v1 |
| Containerisation | Docker + Docker Compose | — | v1 |
| CI/CD | GitHub Actions + GHCR | — | v1 |
| Reverse proxy / TLS | Nginx + Let's Encrypt | — | v1 |
| Cache | Redis 7 + Lettuce | bundled | **v2** |
| External connection pooler | PgBouncer | 1.22.x | **v2** |
| Fine-grained rate limiting | Bucket4j + Redis | 8.x | **v2** |
| Metrics & dashboards | Micrometer + Prometheus + Grafana | — | **v2** |
| Distributed tracing | OpenTelemetry | — | **v2** |
| Frontend UI component lib | Angular Material | 18.x | **v2** |
| E2E tests | Cypress | — | **v2** |

> Items marked **bundled** require zero extra setup — Spring Boot pulls them in automatically.
> Items marked **v2** must not be added until v2 scope begins.

---

## Rationale by Category

### Application Core

**Java 21 LTS**
The minimum version that ships virtual threads as a stable feature (`Thread.ofVirtual()`). For a redirect-heavy service, each request blocks briefly on a DB lookup — virtual threads let you write plain blocking code that scales to thousands of concurrent requests without async/reactive complexity. Using an LTS release means security patches for years without forced upgrades.

**Spring Boot 3.3**
Chosen because it bundles almost everything else in this stack (HikariCP, Hibernate, SLF4J, Actuator, validation, JUnit). For a single developer, the cost of wiring things manually is not worth it. Spring Boot's autoconfiguration handles PostgreSQL, connection pooling, logging format, and health endpoints with a few lines of `application.yml`.
Enable virtual threads with one property: `spring.threads.virtual.enabled=true`.

**Gradle (Kotlin DSL)**
Faster incremental builds than Maven for iterative development. The Kotlin DSL gives type-safe build scripts — autocomplete in the IDE catches build file errors before running. For a single developer who will touch the build file regularly (adding dependencies, configuring Flyway, setting up Testcontainers), this matters.

---

### Frontend

**Angular 18**
Angular's opinionated structure — components, services, dependency injection, built-in routing, HttpClient with interceptors — means the frontend architecture decisions are already made. For a project that will grow its UI over time, this constraint is a feature: there is one correct way to add a route, a service, or an HTTP call. The standalone component API (stable in Angular 17+) removes the NgModule boilerplate that made earlier Angular versions feel heavyweight. TypeScript is enforced by default, which catches contract mismatches between frontend and backend at compile time.

**No Angular Material in v1**
Angular Material is deferred to v2. The v1 UI is two screens: a form to shorten a URL and a page to display the result. Plain HTML + minimal CSS is sufficient. Adding a component library is extra dependency churn for no user-visible value in v1.

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
Coarse throttle on `POST /api/urls` at the proxy layer before the request reaches the application. Zero application code required — configured in the Nginx config file. Sufficient for v1 abuse control.

**Bucket4j + Redis — deferred to v2**
Fine-grained per-IP and per-endpoint rate limiting backed by Redis. Introduces the Redis dependency and distributed state — not justified until v2 scale and abuse data exists.

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
The backend is packaged as a Docker image using a multi-stage build: a `gradle build` stage produces the fat JAR, then an `eclipse-temurin:21-jre-alpine` runtime stage copies it in. This keeps the final image small (target: < 150 MB). The Angular build output (`dist/`) is copied into the Nginx image separately.

**Docker Compose**
Used for two purposes: (1) local development environment (PostgreSQL + app in one command), and (2) local integration test runs matching the CI environment. In v1, Docker Compose is also the production deployment model — one `docker-compose.yml` on the server.

---

### CI/CD

**GitHub Actions + GitHub Container Registry (GHCR)**
Pipeline-as-code in `.github/workflows/`. One workflow file in v1:

```text
push to main
  └── compile + unit tests
        └── integration tests (Testcontainers)
              └── build Docker image
                    └── push to GHCR
                          └── deploy via SSH (docker compose pull && up -d)
```

GHCR is free for public repositories and natively integrated with GitHub Actions — no separate registry credentials to manage.

---

### Infrastructure

**Nginx + Let's Encrypt**
Nginx serves two roles: (1) TLS termination and reverse proxy to the Spring Boot app, and (2) serving the Angular SPA as static files. Let's Encrypt with Certbot provides free automated TLS certificate provisioning and renewal. No cost, no manual cert rotation.

---

## Consequences

- `spring.threads.virtual.enabled=true` must be set explicitly in `application.yml`.
- Flyway migration files are append-only — never modify a version once it has run.
- Testcontainers requires Docker running locally and on CI (GitHub-hosted `ubuntu-latest` includes Docker by default).
- Angular `dist/` output is copied into the Nginx Docker image at build time — two separate images: one for the app, one for the frontend.
- SpringDoc must be disabled in production: `springdoc.api-docs.enabled=false` in `application-prod.yml`.
- No CORS configuration is needed. Angular is served by Nginx and all API calls are proxied through Nginx to the Spring Boot backend — both are on the same origin at all times, including local Docker Compose development.
