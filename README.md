# TinyURL — Production-Grade URL Shortener

A single-region, production-oriented URL shortener built with Spring Boot and Angular, deployed on AWS.

- **Frontend:** [tinyurl.buffden.com](https://tinyurl.buffden.com) — Angular SPA on S3 + CloudFront
- **Backend / Short links:** [go.buffden.com](https://go.buffden.com) — Spring Boot on EC2 behind ALB

![Demo](docs/media/demo/tinyurl-demo.gif)

---

## Architecture

### v1 — Baseline (Implemented)

- Base62 encoded short codes (6–8 chars)
- DB-backed ID generation via PostgreSQL sequence
- Stateless Spring Boot application server
- HTTP 301 (permanent) or 302 (expiring) redirects
- Optional expiration (default 180 days)
- Flyway-managed schema migrations
- Prometheus metrics + structured JSON logging

[![v1 HLD](diagrams/docs/architecture/00-baseline/v1/url-shortener-v1-hld.svg)](diagrams/docs/architecture/00-baseline/v1/url-shortener-v1-hld.svg)

### v2 — Scale & Abuse Resistance (Planned)

- Redis cache (cache-aside pattern)
- Negative caching for invalid codes
- Rate limiting (token bucket)
- Soft delete support
- Custom aliases (feature-flagged)
- Enhanced observability

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
| Containerization | Docker, Docker Compose |
| Cloud | AWS (EC2, RDS, ALB, S3, CloudFront, Route 53, SSM) |
| CI/CD | GitHub Actions → GHCR → EC2 via SSM |
| Observability | Micrometer, Prometheus, CloudWatch |

---

## API

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/urls` | Shorten a URL |
| `GET` | `/{shortCode}` | Redirect to original URL |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Metrics |

---

## Running Locally

### Prerequisites

- Docker & Docker Compose
- Java 21 (for running backend without Docker)
- Node 20+ (for running frontend without Docker)

### Full stack (backend + database + nginx)

```bash
# Copy and fill in required env vars
cp .env.example .env

docker compose up --build
```

App available at `http://localhost:8080`.

### Backend only (with local Postgres)

```bash
cd tinyurl
./gradlew bootRun
```

Backend runs on `http://localhost:8080` by default.

### Run backend tests

```bash
cd tinyurl
./gradlew test
```

> Tests use Testcontainers — Docker must be running.

---

## Environments

| Environment | How to run | URL |
| --- | --- | --- |
| Local (full stack) | `docker compose up` from repo root | `http://localhost:8080` |
| Local (backend only) | `./gradlew bootRun` in `tinyurl/` | `http://localhost:8080` |
| Local (frontend dev) | See [tinyurl-gui/README.md](tinyurl-gui/README.md) | `http://localhost:4200` |
| Production | Auto-deploy on merge to `main` | [go.buffden.com](https://go.buffden.com) |

---

## Project Structure

```text
tinyurl/                # Spring Boot backend
tinyurl-gui/            # Angular frontend
infra/
  nginx/                # Nginx configs (dev + prod)
  postgres/             # DB init scripts
docs/
  architecture/         # ADRs and architecture docs
  deployment/           # AWS deployment phases (A–F)
diagrams/               # Architecture diagrams (SVG)
docker-compose.yml      # Local dev stack
docker-compose.prod.yml # Production stack (no Postgres — uses RDS)
```

---

## Deployment

Production runs on AWS (`us-east-1`). See [`docs/deployment/`](docs/deployment/README.md) for the full deployment runbook (infrastructure provisioning, secrets, CI/CD, observability, hardening).

```text
Route 53
  tinyurl.buffden.com  →  CloudFront  →  S3 (Angular SPA)
  go.buffden.com       →  ALB  →  EC2 (Nginx + Spring Boot)  →  RDS PostgreSQL
```

Docker image: `ghcr.io/buffden/tinyurl-api`
Deploy trigger: merge to `main` via GitHub Actions
