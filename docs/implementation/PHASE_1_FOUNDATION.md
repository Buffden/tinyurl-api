# Phase 1 Foundation (Backend-Only) Execution Guide

This document is the detailed execution guide for **Phase 1: Foundation**.

## Objective

Set up a backend-only runnable baseline for TinyURL with:

- Spring Boot service scaffold
- PostgreSQL schema and migrations
- Local runtime via Docker Compose
- CI build/test validation

## Scope

- Included: backend foundation only
- Excluded: frontend implementation, business endpoint completion, v2 features

## Docker Compose Role in This Phase

- Establish Docker Compose as the canonical backend runtime for local development.
- Keep runtime contracts compatible with single-host EC2 Compose deployment used by architecture docs.
- Keep frontend hosting outside Compose scope (CloudFront + S3).

## Source of Truth

Phase 1 implementation must follow these documents:

- [Implementation Plan](IMPLEMENTATION_PLAN.md)
- [HLD System Design](../hld/system-design/system-design.md)
- [HLD Database v1](../hld/database/v1/database-design.md)
- [LLD Module Structure](../lld/module-structure.md)
- [LLD API Contract](../lld/api-contract.md)
- [Requirements - Functional](../requirements/functional-requirements.md)
- [Requirements - Non-Functional](../requirements/non-functional-requirements.md)

## Execution Steps (with references)

### Step 1: Bootstrap backend project structure

Create the initial Java 21 + Spring Boot 3 project skeleton and package layout.

References:

- [LLD Module Structure](../lld/module-structure.md)
- [HLD System Design](../hld/system-design/system-design.md)
- [ADR-005 Technology Stack](../architecture/00-baseline/adr/ADR-005-technology-stack.md)

Deliverables:

- `src/main/java`, `src/main/resources`, `src/test/java`
- `build.gradle.kts`, `settings.gradle.kts`
- application entrypoint class

### Step 2: Add persistence and migration baseline

Configure PostgreSQL integration and Flyway migrations. Add initial schema migration for `url_mappings` and sequence/indexes.

References:

- [HLD Database v1 Overview](../hld/database/v1/database-design.md)
- [HLD v1 Schema](../hld/database/v1/db-schema.md)
- [Implementation Plan - Phase 1](IMPLEMENTATION_PLAN.md)

Deliverables:

- `src/main/resources/db/migration/V1__init_schema.sql`
- datasource and Flyway config in `application.yml`

### Step 3: Add minimal backend runtime config

Set up profiles, environment variables, and health endpoint exposure for startup validation.

References:

- [LLD API Contract](../lld/api-contract.md)
- [Requirements - Non-Functional](../requirements/non-functional-requirements.md)
- [HLD System Design](../hld/system-design/system-design.md)

Deliverables:

- `/actuator/health` enabled
- local profile config ready

### Step 4: Add local Docker Compose for backend + PostgreSQL

Provide a reproducible local environment with app and db services.

References:

- [LLD C4 Deployment](../lld/c4-diagrams.md)
- [HLD Database v1 Operations](../hld/database/v1/db-operations.md)
- [Implementation Plan - Phase 1](IMPLEMENTATION_PLAN.md)

Deliverables:

- `docker-compose.yml` with app + postgres services
- env and port mapping documented

### Step 5: Add initial integration test (DB connectivity)

Add one integration test to confirm app context and DB connectivity via Testcontainers.

References:

- [Requirements - Functional](../requirements/functional-requirements.md)
- [Requirements - Non-Functional](../requirements/non-functional-requirements.md)
- [Implementation Plan - Phase 1](IMPLEMENTATION_PLAN.md)

Deliverables:

- test class proving DB connection bootstrap succeeds

### Step 6: Add CI pipeline (build + test)

Set up GitHub Actions to run build and tests on push/PR.

References:

- [Implementation Plan - Phase 1](IMPLEMENTATION_PLAN.md)
- [Requirements - Non-Functional](../requirements/non-functional-requirements.md)

Deliverables:

- `.github/workflows/ci-workflows.yml`
- passing checks for compile + tests

## Definition of Done

Phase 1 is complete only when all criteria below are true:

- backend service starts successfully
- Flyway applies initial schema successfully
- PostgreSQL is reachable in local runtime
- health endpoint returns `UP`
- CI runs and passes build + tests

## Validation Commands

```bash
docker compose up -d
./gradlew clean test
./gradlew bootRun
curl http://localhost:8080/actuator/health
```

## Handoff to Phase 2

After Phase 1 is done, move to core v1 endpoint implementation using:

- [LLD API Contract](../lld/api-contract.md)
- [Use Cases v1](../use-cases/v1/)
- [Requirements](../requirements/README.md)
