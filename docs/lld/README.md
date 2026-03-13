# Low-Level Design

> Detailed specifications for implementation. Read the [HLD](../hld/README.md) and [ADRs](../architecture/00-baseline/adr/) before reading anything here.

---

## Documents

| Document | Purpose | Status |
|----------|---------|--------|
| [api-contract.md](api-contract.md) | All HTTP endpoints — request/response schemas, error codes, status codes | Done |
| [c4-diagrams.md](c4-diagrams.md) | C4 model — 5 Excalidraw diagrams with full documentation (see below) | Done |
| [module-structure.md](module-structure.md) | Java package layout, class responsibilities, dependency rules | Done |
| [cache-module.md](cache-module.md) | Cache-aside algorithm, TTL strategy, invalidation, circuit breaker (v2) | Done |
| [rate-limiting-module.md](rate-limiting-module.md) | Bucket4j token bucket config, Redis-backed limits, 429 headers (v2) | Done |

---

## C4 Diagrams

Five Excalidraw diagrams that progressively zoom into the system. Open them in VS Code with the [Excalidraw extension](https://marketplace.visualstudio.com/items?itemName=pomdtr.excalidraw-editor). Full documentation is in [c4-diagrams.md](c4-diagrams.md).

| # | Level | File | Scope |
|---|-------|------|-------|
| 1 | System Context | [c4-level1-system-context.excalidraw](c4-level1-system-context.excalidraw) | TinyURL as a black box — End User, CloudFront, S3 |
| 2 | Container | [c4-level2-container.excalidraw](c4-level2-container.excalidraw) | Deployable/runtime units — CloudFront, S3, Nginx, App, Redis, PgBouncer, PostgreSQL, Observability |
| 3 | Component | [c4-level3-component.excalidraw](c4-level3-component.excalidraw) | Spring Boot internals — DispatcherServlet, middleware, controllers, services, repositories |
| 4 | Code | [c4-level4-code.excalidraw](c4-level4-code.excalidraw) | Java interfaces, classes, and dependency graph across 6 packages |
| 5 | Deployment | [c4-deployment.excalidraw](c4-deployment.excalidraw) | Docker Compose on EC2 — images, ports, volumes, health checks, AWS SSM secrets |

---

## Tech Stack (per ADR-005)

| Layer | Technology |
|-------|------------|
| Runtime | Java 21 LTS + Spring Boot 3.3.x |
| Build | Gradle (Kotlin DSL) 8.x |
| Database | PostgreSQL 16 + PgBouncer 1.22.x |
| ORM | Spring Data JPA + Hibernate (HikariCP) |
| Migrations | Flyway 10 |
| Cache (v2) | Redis 7 + Lettuce |
| Secrets | AWS SSM Parameter Store (SecureString + KMS) |
| Observability | SLF4J + Logback (JSON), Micrometer + Prometheus |
| Container | Docker multi-stage (Gradle → eclipse-temurin:21-jre-alpine) |
| Orchestration | Docker Compose on EC2 |
