# TinyURL — Production-Grade URL Shortener

A single-region, production-oriented URL shortener built with Spring Boot and Angular, deployed on AWS.

- **Frontend:** [tinyurl.buffden.com](https://tinyurl.buffden.com) — Angular SPA on S3 + CloudFront
- **Backend / Short links:** [go.buffden.com](https://go.buffden.com) — Spring Boot on EC2 behind ALB

![Demo](docs/media/demo/tinyurl-demo.gif)

---

## Request Flow

Every request to `go.buffden.com` passes through six layers before any application code runs:

| Layer | Component | Role |
| --- | --- | --- |
| 1 | **Cloudflare** | DDoS mitigation, bot protection, WAF rate limiting, global anycast routing. Kills attack traffic before it reaches AWS — a financial decision as much as a security one. EC2 security group accepts inbound only from Cloudflare's published IP ranges. |
| 2 | **CloudFront** | Routes by path: `/api/*` and `/{code}` forward to the ALB; everything else serves the Angular SPA from S3. Frontend deploys never touch EC2. |
| 3 | **ALB** | Port 80 → 301 to HTTPS. Port 443 terminates TLS, forwards plain HTTP to EC2:80. Health checks on `/actuator/health` — unhealthy instances leave rotation automatically. |
| 4 | **Nginx** | Reverse proxy with per-IP rate limit zones (`create_url`: 40r/m, `redirect`: 30r/m). Known vulnerability scanners (sqlmap, nikto, nuclei, etc.) are blocked by User-Agent and receive `444` — TCP connection closed with zero bytes sent. |
| 5 | **Spring Boot** | Stateless application layer. Input validation, short code generation, redirect logic, Bucket4j per-IP hourly rate cap. |
| 6 | **PostgreSQL** | Single source of truth. Two DB users: Flyway (DDL rights for migrations), application user (SELECT, INSERT, UPDATE, DELETE only — no ALTER, no DROP). |

---

## Architecture

### v1 — Baseline (Implemented)

- Base62 encoded short codes (6–8 chars) generated from a PostgreSQL `bigint` sequence — no UUIDs, no hashing, no collision resolution. The sequence guarantees uniqueness; Base62 keeps codes short. Trade-off: codes are enumerable. Acceptable in v1 — no private content exists.
- HTTP **301** (permanent redirect) when no expiry is set — browser caches it, zero round trip on repeat visits.
- HTTP **302** (temporary redirect) when expiry is set — forces revalidation every time.
- HTTP **410 Gone** on expiry — not 404. Tells browsers, crawlers, and clients the link existed and was intentionally removed.
- Stateless Spring Boot application server
- Flyway-managed schema migrations
- Prometheus metrics + structured JSON logging to CloudWatch

[![v1 HLD](diagrams/docs/architecture/00-baseline/v1/url-shortener-v1-hld.svg)](diagrams/docs/architecture/00-baseline/v1/url-shortener-v1-hld.svg)

### v2 — Scale & Abuse Resistance (Planned)

- Redis cache-aside on the redirect path — target >90% of redirects skip the DB. Cache warming on write, TTL jitter to prevent thundering herd, negative caching for unknown codes.
- Bucket4j upgraded from in-process (v1) to Redis-backed distributed rate limiting — shared state across instances when autoscaling is introduced.
- Cloudflare Turnstile CAPTCHA on URL creation — stops distributed bots that stay under per-IP rate limits.
- Soft delete for malicious link takedowns — full audit trail preserved.
- Custom aliases (4–32 chars, Base62, rate-limited tighter than normal creates).
- Micrometer + Grafana: P95/P99 latency, cache hit ratio, error rate, QPS per endpoint.

[![v2 HLD](diagrams/docs/architecture/00-baseline/v2/url-shortener-v2-hld.svg)](diagrams/docs/architecture/00-baseline/v2/url-shortener-v2-hld.svg)

---

## Stack

| Layer | Technology |
| --- | --- |
| Backend | Spring Boot 3.5, Java 21, Gradle |
| Frontend | Angular 19, Angular Material, SSR |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Reverse proxy | Nginx |
| Rate limiting | Nginx zones + Bucket4j (in-process token bucket, Caffeine cache) |
| Containerization | Docker, Docker Compose |
| Edge | Cloudflare (DDoS, WAF, anycast) |
| Cloud | AWS (EC2, RDS, ALB, S3, CloudFront, SSM, KMS, CloudWatch) |
| CI/CD | GitHub Actions → GHCR → EC2 via SSM RunCommand |
| Image signing | cosign (Sigstore keyless, OIDC-tied) |
| Observability | Micrometer, Prometheus, CloudWatch |

---

## Security

Security is a design constraint, not a checklist. Every decision is cross-referenced against OWASP documentation — see [`docs/security/owasp-compliance.md`](docs/security/owasp-compliance.md).

**Credentials and secrets**
- All credentials in AWS SSM Parameter Store as `SecureString` + KMS. No plaintext secrets in environment variables, config files, or Docker Compose.

**Zero-credential CI/CD**
- GitHub Actions authenticates to AWS via OIDC — no long-lived access keys anywhere. EC2 has an IAM role with SSM access. Deployment issues an SSM `RunCommand` to pull the new image and restart containers. No SSH, no port 22 open.

**Supply chain**
- Every Gradle dependency download is verified against a committed SHA-256 checksum file (`gradle/verification-metadata.xml`). Gradle rejects any JAR that doesn't match — protects against compromised Maven mirrors.
- Docker images are signed after every push using cosign keyless signing (Sigstore). The signature is tied to the GitHub OIDC identity — no signing keys to manage or rotate.

**Response headers (OWASP Secure Headers Project)**
- Full header set applied at Nginx: `Content-Security-Policy` (`default-src 'none'`), `Strict-Transport-Security` (with preload), `Cross-Origin-Opener-Policy`, `Cross-Origin-Resource-Policy`, `Permissions-Policy`, `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`. `server_tokens off` — Nginx version never disclosed.
- Spring Security 6 sets some headers by default. Nginx strips the backend's version via `proxy_hide_header` before adding its own — prevents duplicate or conflicting headers.

**Defense-in-depth rate limiting**
- Layer 1 — Cloudflare WAF: request rate limiting at the edge.
- Layer 2 — Nginx: per-IP rate limit zones per endpoint; returns `444` (TCP close, no response) for malicious scanners.
- Layer 3 — Application: Bucket4j token bucket per IP (20 URL creations/hour), backed by Caffeine in-process cache.

**Input validation**
- URL scheme whitelist (`http://`, `https://` only) — blocks `javascript:` injection.
- 2048-character max enforced at DTO layer and DB `CHECK` constraint.
- Parameterized queries via JPA/Hibernate everywhere — no string concatenation in SQL.
- Standardised error responses — no stack traces in HTTP responses.

**Least privilege**
- Non-root Docker containers, minimal base image (`eclipse-temurin:21-jre-alpine`).
- Two DB users: Flyway gets DDL rights; the application user gets only DML on the URL table. No `ALTER`, `DROP`, or `TRUNCATE`.

---

## CI/CD Pipeline

Three stages run on every push to `main` — all must pass before anything reaches EC2:

1. **Test** — JUnit 5 unit tests + Testcontainers integration tests against a real PostgreSQL instance (not mocks).
2. **Smoke** — Docker Compose spins up the full stack with ephemeral randomised credentials, hits the health endpoint, tears down. If this fails, deploy never runs.
3. **Deploy** — polls for SSM agent availability (handles EC2 cold starts), issues `RunCommand` to pull the new image from GHCR and restart containers. Waits for command completion and exits non-zero on failure.

After push, the image is signed with cosign before the deploy stage runs.

---

## API

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/urls` | Shorten a URL |
| `GET` | `/{shortCode}` | Redirect to original URL |

---

## Running Locally

### Prerequisites

- Docker & Docker Compose
- Java 21 (for running backend without Docker)

### Full stack (backend + database + nginx)

```bash
docker compose up --build
```

App available at `http://localhost:8080`.

### Backend only

```bash
cd tinyurl
./gradlew bootRun
```

### Run tests

```bash
cd tinyurl
./gradlew test
```

> Tests use Testcontainers — Docker must be running.

---

## Project Structure

```text
tinyurl/                # Spring Boot backend
tinyurl-gui/            # Angular frontend
infra/
  nginx/                # Nginx configs (dev + prod)
  postgres/             # DB init scripts
docs/
  architecture/         # ADRs, v1/v2 architecture docs, security hardening backlog
  security/             # OWASP compliance checklist, threat model, DB least privilege
  deployment/           # AWS deployment runbook (phases A–F)
diagrams/               # Architecture diagrams (SVG)
docker-compose.yml      # Local dev stack
```

---

## Deployment

Production is deployed on AWS. See [`docs/deployment/`](docs/deployment/README.md) for the full runbook.
