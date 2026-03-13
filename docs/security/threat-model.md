# Threat Model

> Security analysis for TinyURL. Identifies threats, attack surfaces, and mitigations across the system. Referenced by Phase 8 (Hardening) of the [Implementation Plan](../implementation/IMPLEMENTATION_PLAN.md).

---

## Scope

- **In scope**: The TinyURL application (Spring Boot), PostgreSQL, Redis (v2), Nginx reverse proxy, Docker Compose deployment on EC2, and the interactions between them.
- **Out of scope**: AWS account-level security (IAM policies, VPC configuration), DNS registrar security, CI/CD pipeline security (GitHub Actions), and end-user device security.

---

## Trust Boundaries

```
┌─────────────────────────────────────────────────────────┐
│  Internet (untrusted)                                   │
│    └── End User (browser, curl, bot)                    │
└────────────────────┬────────────────────────────────────┘
                     │ HTTPS (TLS 1.2+)
┌────────────────────▼────────────────────────────────────┐
│  DMZ / Reverse Proxy                                    │
│    └── Nginx (TLS termination, rate limiting)           │
└────────────────────┬────────────────────────────────────┘
                     │ HTTP (plaintext, internal network)
┌────────────────────▼────────────────────────────────────┐
│  Application Tier (trusted)                             │
│    └── Spring Boot App (stateless)                      │
│         ├── → PostgreSQL (internal network)             │
│         └── → Redis (internal network, v2)              │
└─────────────────────────────────────────────────────────┘
```

**Key boundaries**:
1. Internet → Nginx: untrusted input enters the system.
2. Nginx → App: input has passed TLS and basic rate limiting, but is still untrusted from an application perspective.
3. App → PostgreSQL / Redis: trusted internal communication. No TLS (Docker internal network). Credentials secured via AWS SSM in production.

---

## STRIDE Threat Analysis

### 1. Spoofing

| Threat | Description | Severity | Mitigation | Version |
|---|---|---|---|---|
| T-S01 | Attacker impersonates another client IP to bypass rate limiting | Medium | Nginx sets `X-Forwarded-For`; app trusts only the first hop. Single-proxy deployment prevents header injection from the internet. | v1 |
| T-S02 | Attacker accesses admin endpoints without authentication | High | Admin endpoints gated behind authentication (`SecurityConfig`). No admin endpoints in v1. | v2 |
| T-S03 | Attacker forges database credentials | High | Credentials stored in AWS SSM Parameter Store (SecureString + KMS). Not in environment variables or config files in production. | v1 |

### 2. Tampering

| Threat | Description | Severity | Mitigation | Version |
|---|---|---|---|---|
| T-T01 | Attacker modifies request body in transit | High | All external traffic over HTTPS (TLS 1.2+). Nginx terminates TLS. | v1 |
| T-T02 | Attacker modifies data in PostgreSQL directly | High | DB accessible only on Docker internal network. No port exposed to host in production. Strong password via SSM. | v1 |
| T-T03 | Attacker poisons Redis cache with malicious URL mappings | High | Redis accessible only on Docker internal network. No port exposed to host in production. Redis `requirepass` set via SSM in production. | v2 |

### 3. Repudiation

| Threat | Description | Severity | Mitigation | Version |
|---|---|---|---|---|
| T-R01 | Attacker creates malicious short URLs and denies it | Medium | `created_ip` and `user_agent` columns stored on every creation. Structured logs with correlation IDs trace each request. | v1 |
| T-R02 | Admin soft-deletes a URL and denies it | Low | `deleted_at` timestamp and audit logging. Future: admin action audit trail. | v2 |

### 4. Information Disclosure

| Threat | Description | Severity | Mitigation | Version |
|---|---|---|---|---|
| T-I01 | Short code enumeration reveals all stored URLs | Medium | Short codes are Base62-encoded sequential IDs — enumerable in theory. Mitigation: rate limiting on redirect path (200 req/s Nginx, 100 req/s app). Short codes are not secret — the original URL is semi-public (anyone with the short link can see the destination). | v1 |
| T-I02 | Error responses leak stack traces or internal paths | Medium | `GlobalExceptionHandler` returns standardized `ErrorResponse` objects. Stack traces logged server-side only (never in HTTP response). `server.error.include-stacktrace=never` in `application.yml`. | v1 |
| T-I03 | Actuator endpoints expose sensitive application internals | Medium | Only `/actuator/health` is exposed. All other actuator endpoints disabled or secured: `management.endpoints.web.exposure.include=health`. | v1 |
| T-I04 | Database connection string visible in logs or config | Medium | Connection credentials from AWS SSM in production. Logging configuration masks sensitive properties. Spring Boot's `@Value` fields for passwords are not logged. | v1 |

### 5. Denial of Service

| Threat | Description | Severity | Mitigation | Version |
|---|---|---|---|---|
| T-D01 | Volumetric attack on create endpoint exhausts DB connections | High | Nginx rate limit: 10 req/min per IP with burst=5. App-layer Bucket4j: 5 req/min per IP (v2). HikariCP connection pool with bounded size. | v1 + v2 |
| T-D02 | Volumetric attack on redirect endpoint overwhelms DB | High | Nginx rate limit: 200 req/s per IP. Cache-aside absorbs >90% of reads (v2). PgBouncer connection pooling (v2). | v1 + v2 |
| T-D03 | Cache stampede — many requests for the same uncached key hit DB simultaneously | Medium | Acceptable in v2 scope (single Redis instance). If needed in future: probabilistic early expiration or Redis `SETNX`-based locking. | v2 |
| T-D04 | Attacker floods with random short codes to fill negative cache and exhaust Redis memory | Medium | Redis `maxmemory-policy allkeys-lru` evicts cold keys. Negative cache TTL is short (60s). Nginx rate limiting on redirect path caps throughput. | v2 |
| T-D05 | Slowloris or slow-read attacks tie up Nginx connections | Medium | Nginx `client_body_timeout`, `client_header_timeout`, and `keepalive_timeout` set to reasonable values (10s, 10s, 65s). | v1 |

### 6. Elevation of Privilege

| Threat | Description | Severity | Mitigation | Version |
|---|---|---|---|---|
| T-E01 | Attacker escalates from public API to admin functionality | High | No admin endpoints in v1. In v2, admin endpoints require authentication (`SecurityConfig`). Role-based access if admin features expand. | v2 |
| T-E02 | Container escape from app container to host | Low | Docker containers run as non-root user. Minimal base image (`eclipse-temurin:21-jre-alpine`). No `--privileged` flag. | v1 |

---

## Input Validation Threats

URL shorteners accept user-supplied URLs, making input validation critical.

| Threat | Description | Severity | Mitigation |
|---|---|---|---|
| T-IV01 | **Open redirect abuse** — attacker creates short URLs pointing to phishing sites | High | This is an inherent property of URL shorteners. Mitigation: log `created_ip` and `user_agent` for abuse investigation. Future: URL reputation checking or blocklist. Not solvable at the application layer without a URL classification service. |
| T-IV02 | **JavaScript URL injection** — `javascript:alert(1)` as the original URL | High | URL validation requires `http://` or `https://` scheme only. Any other scheme is rejected with `INVALID_URL`. |
| T-IV03 | **Excessively long URLs** — 10MB URL in request body | Medium | `original_url` max length: 2048 characters (validated at DTO layer, enforced by DB `CHECK` constraint). Spring Boot `server.max-http-header-size` and `spring.servlet.multipart.max-request-size` limit request size. |
| T-IV04 | **SQL injection via short code or URL** | Critical | All DB access uses parameterized queries (JPA/Hibernate). No string concatenation in SQL. The `nextSequenceVal()` raw SQL uses no user input. |
| T-IV05 | **XSS via stored URL** — malicious URL rendered in UI | Medium | URLs are never rendered as HTML. The API returns JSON. The Angular frontend uses built-in sanitization. Redirect responses set `Location` header only — no HTML body with user content. |
| T-IV06 | **Custom alias injection** (v2) — alias containing special characters | Medium | Alias validation: Base62 characters only (`[0-9a-zA-Z]`), 4–32 characters, checked by regex constraint. DB `CHECK` constraint as a second layer. |
| T-IV07 | **SSRF via original URL** — attacker provides `http://169.254.169.254/...` (AWS metadata) or internal network addresses | Medium | The application does **not** fetch or validate the destination URL at creation time — it only stores it. No SSRF risk because no server-side request is made to the user-supplied URL. |

---

## Infrastructure Threats

| Threat | Description | Severity | Mitigation |
|---|---|---|---|
| T-INF01 | Unencrypted traffic between client and server | High | Nginx enforces HTTPS. HTTP requests are redirected to HTTPS (`return 301 https://$host$request_uri`). HSTS header set. |
| T-INF02 | Outdated dependencies with known CVEs | Medium | Dependabot enabled on GitHub repository. Gradle dependency verification. Base Docker image updated regularly. |
| T-INF03 | Docker image contains unnecessary tools or attack surface | Low | Multi-stage build: final image is `eclipse-temurin:21-jre-alpine` (no JDK, no build tools, no shell utilities beyond busybox). |
| T-INF04 | Redis data not persisted — restart loses cache | Low | Redis is a cache, not a data store. Loss of cache causes temporary increased DB load, not data loss. Acceptable. |

---

## Security Controls Summary

| Control | Layer | Version | Status |
|---|---|---|---|
| HTTPS (TLS 1.2+) | Nginx | v1 | Required |
| HSTS header | Nginx | v1 | Required |
| Rate limiting (Nginx) | Nginx | v1 | Required |
| Rate limiting (Bucket4j + Redis) | Application | v2 | Required |
| Input validation (URL scheme, length) | Application (DTO) | v1 | Required |
| Input validation (alias format) | Application (DTO) | v2 | Required |
| Parameterized queries | Application (JPA) | v1 | Required |
| Standardized error responses (no stack traces) | Application | v1 | Required |
| Actuator endpoint restriction | Application | v1 | Required |
| Non-root Docker containers | Infrastructure | v1 | Required |
| Minimal Docker base image | Infrastructure | v1 | Required |
| Credential management (AWS SSM) | Infrastructure | v1 (prod) | Required |
| Redis authentication | Infrastructure | v2 (prod) | Required |
| Structured audit logging | Application | v1 | Required |
| Admin authentication | Application | v2 | Required |
| Dependency scanning (Dependabot) | CI/CD | v1 | Required |

---

## Accepted Risks

| Risk | Rationale |
|---|---|
| Short code enumeration | Short codes are semi-public. Rate limiting bounds the enumeration rate. The original URLs are not sensitive — anyone with the short link already knows the destination. |
| Open redirect abuse | Inherent to URL shorteners. Mitigated by audit logging, not prevention. A URL reputation service is out of scope. |
| No TLS between Nginx and app | Internal Docker network only. Adding mTLS is complexity that does not match the single-host deployment model. Revisit if the architecture moves to multi-host. |
| No Redis TLS | Internal Docker network only. Same rationale as above. |
| Cache stampede for hot keys | Unlikely at v2 scale. Acceptable latency impact. Solvable with locking if observed in production. |

---

## Review Schedule

This threat model should be reviewed:

- Before each phase go-live (especially Phase 8 — Hardening).
- When new endpoints or features are added.
- When the deployment architecture changes (e.g., multi-region, Kubernetes).
- Annually, or after any security incident.
