# Phase 9: Hardening (Security & Production Readiness)

## Objective

Prepare the v2 system for production with security validation, performance verification, and operational readiness.

## MVP-Only Execution Rule

- For the current timeline, implement only content before `## Optional (Post-MVP)`.
- Treat `## Optional (Post-MVP)` items as backlog and do not block release.

## Depends On

- [Phase 1 Foundation](PHASE_1_FOUNDATION.md)
- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 3 Observability](PHASE_3_OBSERVABILITY.md)
- [Phase 5 Cache Layer (v2)](PHASE_5_CACHE_LAYER_V2.md)
- [Phase 6 Rate Limiting (v2)](PHASE_6_RATE_LIMITING_V2.md)
- [Phase 7 Custom Aliases (v2)](PHASE_7_CUSTOM_ALIASES_V2.md)
- [Phase 8 Cleanup and Archival (v2)](PHASE_8_CLEANUP_AND_ARCHIVAL_V2.md)

## Source References

- [Threat Model](../security/threat-model.md)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)
- [LLD C4 Diagrams](../lld/c4-diagrams.md)
- [ADR-005 Technology Stack](../architecture/00-baseline/adr/ADR-005-technology-stack.md)

## In Scope

- security hardening and threat remediation
- load/performance and reliability testing
- deployment and runbook readiness
- final documentation consistency check

## Out of Scope

- major feature additions
- architecture rewrites unrelated to hardening goals

## Execution Steps

### Step 1: Security remediation pass

References:

- [Threat Model](../security/threat-model.md)

Tasks:

- Validate controls for injection, abuse, and data exposure paths
- Close critical/high-risk items before release

### Step 2: Performance and resilience validation

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

Tasks:

- Run load tests for v1/v2 targets
- Validate latency and error-rate SLOs
- Exercise failover/degraded scenarios

### Step 3: Deployment readiness

References:

- [LLD C4 Diagrams](../lld/c4-diagrams.md)

Tasks:

- Validate runtime configuration and secrets handling
- Ensure operational runbook coverage for incident cases

### Step 4: Final documentation sync

References:

- [Requirements README](../requirements/README.md)
- [HLD README](../hld/README.md)
- [LLD README](../lld/README.md)

Tasks:

- Confirm docs match implemented behavior
- Freeze release notes and known limitations

## Deliverables

- security review evidence and resolved findings list
- load test report and tuning notes
- production readiness checklist
- synchronized final docs/runbook

## Acceptance Criteria

- No unresolved critical/high security findings
- System meets latency and reliability targets
- Operational readiness validated for go-live

---

## Part 2: Implementation Deep Dive

### Security Hardening Checklist

**1. Input Validation & Output Encoding**

Target URL validation with URL parsing:
Parse the submitted URL using `java.net.URI` and verify the scheme is `http` or `https`, the host is non-empty, and the total length is within 2048 characters. Reject any URL that fails parsing or contains disallowed schemes (e.g., `javascript:`, `data:`). This validation already exists in Phase 2 but should be reviewed and hardened here against edge cases identified in the threat model.

HTML entity encoding in responses:
Ensure all user-supplied values reflected in API responses (e.g., the `longUrl` field echoed back in the create response) are serialized as JSON strings by Jackson without manual HTML escaping. Verify Jackson's `ESCAPE_NON_ASCII` and `character-escaping` settings are appropriate. Do not render user input in HTML templates — the API is JSON-only, so XSS via response body is not applicable, but confirm no admin or error pages accidentally reflect raw input.

**2. SQL Injection Prevention**

Verify all database queries use prepared statements (Hibernate/JPA default):
Review every repository method and confirm none use string concatenation to build JPQL or native queries. Hibernate's `@Query` with named parameters and Spring Data derived queries are both safe. Enable Hibernate SQL logging in the test profile and inspect the output to confirm bind parameters (`?`) are used throughout. Flag any `nativeQuery = true` usages for extra scrutiny.

**3. CORS & Cross-Origin Security**

Spring Security CORS configuration:
Configure a `CorsConfigurationSource` bean that allows only the production frontend origin (e.g., the CloudFront domain) for `POST /api/urls` and `GET /{short_code}`. Allow only the required HTTP methods and headers. Set `allowCredentials` to false (no cookies/auth). In local and staging profiles, permit `localhost:4200` in addition to the production origin. Do not use a wildcard `*` origin in any non-local profile.

**4. Authentication & Authorization Scope**

No additional authentication mechanism is introduced in this phase.

- Keep public endpoints as currently designed (create + redirect).
- Restrict actuator and internal operational endpoints using existing Spring profile and network policy.
- Defer API key or OAuth flows unless explicitly added as a future requirement.

**5. Sensitive Data Protection**

Mask sensitive fields in logs:
In `RequestObservabilityFilter`, never log the full `longUrl` value — truncate to 100 characters maximum and strip query parameters that may contain tokens or credentials. Ensure the `Authorization` header is never written to MDC. Add a Logback `MaskingConverter` or Promtail pipeline stage to redact any value matching common secret patterns (e.g., `Bearer ...`, `password=...`) as a defence-in-depth measure.

Filter sensitive fields from error responses:
Review all `ErrorResponse` payloads returned by `GlobalExceptionHandler` and confirm they contain only the error `code` and a safe static `message`. Stack traces, internal class names, SQL error messages, and raw exception messages must never appear in API responses. The `INTERNAL_ERROR` catch-all already suppresses this, but verify that no other handler leaks internal details.

**6. HTTP Security Headers**

Configure all security headers in Spring Security (see SecurityConfig above):

- `Strict-Transport-Security` (HSTS): Enforce HTTPS
- `X-Frame-Options: DENY`: Prevent clickjacking
- `X-Content-Type-Options: nosniff`: Prevent MIME sniffing
- `X-XSS-Protection: 1; mode=block`: XSS protection
- `Content-Security-Policy`: Restrict resource loading
- `Referrer-Policy: strict-origin-when-cross-origin`: Limit referrer leakage

---

### Performance & Resilience Testing

**Load Test Plan (using k6):**
Write a k6 script with two scenarios: a steady-state scenario ramping up to 500 virtual users over 2 minutes then holding for 5 minutes (targeting the redirect endpoint at ~1000 RPS), and a spike scenario that jumps to 1000 VUs for 30 seconds. Define pass/fail thresholds: p95 response time below 500ms, p99 below 1s, and error rate below 1%. Include both `POST /api/urls` (create) and `GET /{short_code}` (redirect) in the traffic mix at roughly a 1:10 ratio.

**Run Load Test:**
Execute the k6 script from a machine with sufficient network bandwidth (or an EC2 instance in the same region as the target). Point the test at the staging environment, not production. Capture the summary output and store the HTML report as a test artifact. Compare p95/p99 values against the NFR targets before signing off on Phase 9.

**Failure Scenario Testing:**
Test three degraded-mode scenarios manually: (1) stop the Redis container mid-test and confirm redirects continue via DB fallback with no 5xx errors; (2) stop the Postgres container and confirm the API returns `503 SERVICE_UNAVAILABLE` with a `Retry-After` header; (3) send a burst of requests above the rate limit threshold and confirm `429` responses appear with `Retry-After` and that the health endpoint remains accessible throughout.

---

### Deployment Readiness & Operational Procedures

**Configuration Management (Environment + Profile Based):**
All secrets (DB password, Redis password, Grafana admin password) must be injected via environment variables and never committed to source control. Use Spring profiles (`default`, `prod`) to control which `application-{profile}.yaml` is loaded. Verify the prod profile disables the `prometheus` actuator endpoint, enables `when_authorized` health details, and sets log level to `INFO`. Document all required environment variables in the project `README` with descriptions and example values (no real values).

**Health Check Endpoints:**
Confirm `/actuator/health/readiness` includes the `db` and `diskSpace` components and returns `OUT_OF_SERVICE` if either is unhealthy. Confirm `/actuator/health/liveness` returns `DOWN` only on a fatal JVM-level condition, not on dependency failures. Verify these endpoints are reachable by the load balancer or container orchestrator without authentication, and that the `prometheus` and `metrics` endpoints are not exposed in the prod profile.

**Production Incident Runbook:**
Document the following playbooks: (1) high error rate — check recent deploys, inspect logs by `correlation_id`, verify DB and Redis health; (2) latency spike — inspect DB connection pool saturation, Redis command latency, and host CPU/memory; (3) cleanup job missed — check distributed lock state in Redis, review `cleanup-` thread logs; (4) rollback procedure — redeploy previous Docker image tag and run `docker compose up -d` with the prior image reference.

---

### Documentation & Release Readiness

**Production Deployment Checklist:**
Before go-live, verify each of the following: all environment variables set and secrets rotated from defaults; Flyway migrations applied cleanly on the prod DB; readiness and liveness probes returning `UP`; Prometheus metrics visible in Grafana; all Phase 9 acceptance criteria checked off; load test report reviewed and p95/p99 targets met; security headers confirmed via browser dev tools or `curl -I`; CloudFront cache behaviors and HTTPS redirect verified; on-call runbook reviewed by at least one other team member; and go-live sign-off obtained from security, ops, and product.

---

## Optional (Post-MVP)

- Introduce authentication (API key/OAuth) for privileged/internal endpoints.
- Add canary or blue/green deployment workflow.
- Add WAF advanced managed rules and bot controls.
- Add automated chaos/failure-injection test suite in CI.
- Add centralized SIEM integration and compliance-oriented audit trails.

## Updated Acceptance Criteria

- ✅ No unresolved critical/high security findings (OWASP top 10 covered)
- ✅ Input validation hardened across all endpoints
- ✅ SQL injection protection verified (no string concatenation)
- ✅ All HTTP security headers configured (CSP, HSTS, X-Frame-Options, etc.)
- ✅ Sensitive data masked in logs and error responses
- ✅ CORS policy restricted to production origins only
- ✅ Load test completed: p95 < 500ms, p99 < 1s at 1000 RPS
- ✅ Error rate < 1% sustained at 500 VU (1000 RPS)
- ✅ Failure scenarios tested (cache down, DB down, Redis unavailable)
- ✅ Deployment checklist completed and signed off
- ✅ Production runbook with incident playbooks documented
- ✅ Health check endpoints (liveness, readiness) verified
- ✅ Configuration management via profile + environment variables (no secrets in code)
- ✅ All documentation synchronized with implementation
- ✅ Go-live sign-off obtained (security, ops, product)
