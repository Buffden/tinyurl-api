# Implementation Plan

This document defines the phased implementation plan for TinyURL. Each phase has clear goals, deliverables, and acceptance criteria.

---

## Phase Overview

| Phase | Name | Goal | Dependencies |
|---|---|---|---|
| 1 | Foundation | Project scaffolding, CI/CD, database schema | None |
| 2 | Core API (v1) | URL shortening and redirect — production-ready baseline | Phase 1 |
| 3 | Observability | Logging, metrics, health checks, alerting | Phase 2 |
| 4 | Cache Layer (v2) | Redis cache-aside for redirect path | Phase 2 |
| 5 | Rate Limiting (v2) | Nginx and app-layer rate limiting | Phase 2 |
| 6 | Custom Aliases (v2) | Feature-flagged custom alias support | Phase 2, Phase 4 |
| 7 | Cleanup and Archival (v2) | Scheduled expiration cleanup and data archival | Phase 2 |
| 8 | Hardening | Security review, load testing, documentation finalization | Phases 1-7 |

---

## Phase 1: Foundation

**Goal**: Set up the project skeleton, CI pipeline, and database schema.

### Deliverables

- [ ] Initialize project repository with chosen tech stack
- [ ] Configure build tool and dependency management
- [ ] Set up CI pipeline (build, test, lint on every push)
- [ ] Create PostgreSQL schema migration (url_mappings table, sequence, indexes)
- [ ] Implement database migration tooling (Flyway, Alembic, or equivalent)
- [ ] Create Docker Compose for local development (app + PostgreSQL)
- [ ] Write initial integration test verifying DB connectivity

### Acceptance Criteria

- `docker compose up` starts the application and database.
- CI pipeline runs on push and blocks merge on failure.
- Database schema matches [database-design.md](hld/database-design.md).

---

## Phase 2: Core API (v1)

**Goal**: Implement the URL shortening and redirect endpoints — the production-ready v1 baseline.

### Deliverables

- [ ] `POST /create` — URL shortening endpoint
  - URL validation (format, length)
  - DB sequence-based ID generation
  - Base62 encoding
  - Configurable expiry (default 180 days)
  - 201 response with short URL
- [ ] `GET /<short_code>` — Redirect endpoint
  - DB lookup by short code
  - Expiry validation
  - Conditional 301/302 redirect
  - 404 for missing, 410 for expired
- [ ] `GET /health` — Health check endpoint
- [ ] Repository layer with full unit tests
- [ ] Integration tests for create and redirect flows
- [ ] API documentation (OpenAPI/Swagger)

### Acceptance Criteria

- All functional requirements (FR-001 through FR-005) are satisfied.
- P95 response time < 100 ms for both endpoints (local benchmarks).
- Test coverage > 80% for business logic.

---

## Phase 3: Observability

**Goal**: Add production-grade logging, metrics, and health monitoring.

### Deliverables

- [ ] Structured logging (JSON format) with correlation IDs
- [ ] Application metrics (request count, latency percentiles, error rate)
- [ ] Database connection pool metrics
- [ ] `/health` endpoint with readiness and liveness probes
- [ ] Alerting rules for error rate > 1% and P99 latency > 500 ms
- [ ] Log aggregation setup (ELK, CloudWatch, or equivalent)

### Acceptance Criteria

- Logs include timestamp, level, correlation ID, and request metadata.
- Metrics endpoint exposes Prometheus-compatible metrics.
- Health endpoint returns component-level status (app, DB).

---

## Phase 4: Cache Layer (v2)

**Goal**: Add Redis cache-aside to the redirect path to reduce DB load and improve latency.

### Deliverables

- [ ] Redis integration (connection pooling, configuration)
- [ ] Cache-aside read on redirect path (lookup short code in Redis first)
- [ ] Cache population after DB miss
- [ ] Cache warming after URL creation
- [ ] Negative caching for unknown short codes (TTL: 30-120s)
- [ ] TTL strategy with jitter
- [ ] Circuit breaker for Redis failures (degrade to DB-only)
- [ ] Cache hit/miss metrics
- [ ] Integration tests for cache hit, miss, and failure scenarios
- [ ] Docker Compose updated with Redis service

### Acceptance Criteria

- Cache hit ratio > 90% under simulated load.
- Redis failure does not cause application errors (graceful degradation).
- Module design matches [cache-module.md](lld/cache-module.md).

---

## Phase 5: Rate Limiting (v2)

**Goal**: Protect the system from abuse with multi-layer rate limiting.

### Deliverables

- [ ] Nginx rate limiting configuration (create: 5/min, redirect: 200/s)
- [ ] Application-layer token bucket rate limiter (Redis-backed)
- [ ] Differentiated limits for standard and custom alias endpoints
- [ ] 429 response with `Retry-After` and rate limit headers
- [ ] Rate limit metrics (429 count per endpoint, per IP)
- [ ] Health endpoint excluded from rate limiting
- [ ] Integration tests for rate limit enforcement

### Acceptance Criteria

- Exceeding rate limit returns 429 with correct headers.
- Health endpoint is never rate-limited.
- Module design matches [rate-limiting-module.md](lld/rate-limiting-module.md).

---

## Phase 6: Custom Aliases (v2)

**Goal**: Allow users to specify custom short codes, gated behind a feature flag.

### Deliverables

- [ ] Feature flag configuration for custom aliases
- [ ] Alias validation (Base62, 4-32 chars, no reserved words)
- [ ] Uniqueness check before insertion
- [ ] 409 Conflict response for taken aliases
- [ ] Stricter rate limit for custom alias requests (2/min)
- [ ] Namespace reservation (aliases are never recycled)
- [ ] Unit and integration tests

### Acceptance Criteria

- Custom aliases work when feature flag is enabled.
- Disabled feature flag returns 400.
- Reserved words are rejected.
- Taken aliases return 409.

---

## Phase 7: Cleanup and Archival (v2)

**Goal**: Implement scheduled cleanup of expired URL mappings.

### Deliverables

- [ ] Scheduled cleanup job (configurable interval, default 24h)
- [ ] Batched soft-delete of expired rows (7-day grace period)
- [ ] Cache invalidation for cleaned-up entries
- [ ] Cleanup metrics (rows processed, duration, errors)
- [ ] Archival strategy for soft-deleted rows older than 90 days
- [ ] Integration tests for cleanup behavior

### Acceptance Criteria

- Expired rows are soft-deleted after the grace period.
- Cleanup job processes rows in batches without long-running transactions.
- Cleanup failure does not affect serving traffic.

---

## Phase 8: Hardening

**Goal**: Prepare the system for production readiness.

### Deliverables

- [ ] Security review against [threat-model.md](security/threat-model.md)
- [ ] Input sanitization audit
- [ ] Load testing (target: 5K QPS for v1, 20K QPS for v2)
- [ ] Performance tuning based on load test results
- [ ] Nginx TLS configuration (HTTPS only)
- [ ] Final documentation review and update
- [ ] Runbook for common operational tasks
- [ ] Deployment to `tinyurl.buffden.com`

### Acceptance Criteria

- System handles target QPS with P99 < 200 ms.
- No critical or high security findings remain.
- All documentation is current and accurate.
- System is live at `tinyurl.buffden.com`.

---

## Timeline Estimate

| Phase | Estimated Duration |
|---|---|
| Phase 1: Foundation | 2-3 days |
| Phase 2: Core API | 3-5 days |
| Phase 3: Observability | 2-3 days |
| Phase 4: Cache Layer | 2-3 days |
| Phase 5: Rate Limiting | 1-2 days |
| Phase 6: Custom Aliases | 1-2 days |
| Phase 7: Cleanup | 1-2 days |
| Phase 8: Hardening | 3-5 days |
| **Total** | **~15-25 days** |

Estimates assume a single developer working part-time. Phases 4-7 can be parallelized if multiple developers are available.
