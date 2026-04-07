# TinyURL v1 — Deployment Overview

**Frontend:** `tinyurl.buffden.com` → S3 + CloudFront (Angular SPA)
**Backend + Redirects:** `go.buffden.com` → ALB → EC2 → Spring Boot
**Short URL format:** `https://go.buffden.com/{code}`
**Region:** `us-east-1` (N. Virginia)
**Estimated cost:** ~$54/month

---

## Phase Index

| Phase | File | Goal | When |
|---|---|---|---|
| A | [PHASE_A_INFRASTRUCTURE.md](PHASE_A_INFRASTRUCTURE.md) | Provision all AWS resources | Week 1 |
| B | [PHASE_B_SECRETS_AND_CONFIG.md](PHASE_B_SECRETS_AND_CONFIG.md) | Secrets, CORS, prod config, OIDC | Week 1 (after A) |
| C | [PHASE_C_FIRST_MANUAL_DEPLOY.md](PHASE_C_FIRST_MANUAL_DEPLOY.md) | First manual end-to-end deploy | Week 2 |
| D | [PHASE_D_CICD_AUTOMATION.md](PHASE_D_CICD_AUTOMATION.md) | GitHub Actions auto-deploy pipelines | Week 2–3 |
| E | [PHASE_E_OBSERVABILITY.md](PHASE_E_OBSERVABILITY.md) | CloudWatch logs and alarms | Week 3 |
| F | [PHASE_F_HARDENING.md](PHASE_F_HARDENING.md) | Security headers, protection, rollback | Week 3–4 |

---

## Architecture

```
                    ┌─────────────────────────────────────────────┐
                    │         Cloudflare (DNS + Proxy)             │
                    │  buffden.com nameservers                     │
                    │  tinyurl.buffden.com  →  CloudFront (proxied)│
                    │  go.buffden.com       →  ALB (proxied)       │
                    └──────────┬──────────────────────┬───────────┘
                               │                      │
               ┌───────────────▼──────┐   ┌───────────▼──────────────┐
               │   CloudFront CDN     │   │  Application Load        │
               │   tinyurl.buffden.com│   │  Balancer (ALB)          │
               │   Origin: S3 bucket  │   │  go.buffden.com          │
               │   PriceClass_100     │   │  :443 → EC2:80           │
               │   HTTPS only         │   │  :80  → redirect to 443  │
               └──────────────────────┘   └───────────┬──────────────┘
                         │                            │
               ┌──────────▼──────────┐   ┌────────────▼──────────────────┐
               │   S3 Bucket         │   │   EC2 t3.small (us-east-1a)   │
               │   tinyurl-spa-prod  │   │   Ubuntu 22.04 LTS            │
               │   Block public      │   │   Nginx + Spring Boot         │
               │   access (OAC)      │   │   (Docker Compose)            │
               └─────────────────────┘   └────────────┬──────────────────┘
                                                       │
                                         ┌─────────────▼─────────────────┐
                                         │   RDS PostgreSQL 16           │
                                         │   db.t3.micro, private subnet │
                                         └───────────────────────────────┘
```

## Traffic Flows

| Request | Route |
|---|---|
| `https://tinyurl.buffden.com` | Cloudflare → CloudFront → S3 (Angular SPA) |
| `https://tinyurl.buffden.com/*` | CloudFront → S3 → `index.html` (Angular router) |
| `POST https://go.buffden.com/api/urls` | Cloudflare → ALB → Nginx → Spring Boot → RDS |
| `GET https://go.buffden.com/{code}` | Cloudflare → ALB → Nginx → Spring Boot → 301/302 |
| `http://` any domain | Redirect to `https://` |

---

## Key Decisions

| Decision | Value |
|---|---|
| Frontend | S3 + CloudFront at `tinyurl.buffden.com` |
| Backend | ALB → EC2 at `go.buffden.com` |
| Short URL format | `https://go.buffden.com/{code}` |
| API called by Angular | `https://go.buffden.com/api` |
| DNS | Cloudflare (nameservers set at Namecheap) |
| Region | `us-east-1` (N. Virginia) |
| GitHub username | `buffden` |
| Docker image | `ghcr.io/buffden/tinyurl-api` |
| Deploy trigger | Auto on merge to `main` |
| EC2 access | SSM Session Manager + EC2 Instance Connect (no SSH) |
| AWS auth for CI | OIDC (no long-lived keys) |
| IaC | Manual AWS Console (v1), Terraform (v2) |
| Notifications | None for v1 |
| CloudFront on backend | Deferred to v2 |
| v2 Redis | Same EC2 t3.small |

---

## Cost Estimate

| Component | Spec | Monthly |
|---|---|---|
| EC2 t3.small | 2 vCPU, 2 GB | ~$15 |
| RDS db.t3.micro | PostgreSQL 16, 5 GB | ~$15 |
| ALB | 1 load balancer | ~$18 |
| S3 | <100 MB assets | <$1 |
| CloudFront (SPA) | Low traffic | ~$1 |
| Cloudflare | DNS + proxy (free plan) | $0 |
| CloudWatch | Alarms + logs | ~$3 |
| ACM / SSM | Free tiers | $0 |
| **Total** | | **~$54/month** |

---

## What's Missing Before Production

| Item | Phase |
|---|---|
| CORS config in Spring Boot | B |
| `environment.prod.ts` → `https://go.buffden.com/api` | B |
| `docker-compose.prod.yml` (no Postgres) | B |
| `nginx.prod.conf` | B |
| All AWS infrastructure provisioned | A |
| SSM Parameter Store populated | B |
| OIDC trust between GitHub and AWS | B |
| Docker image pushed to GHCR | C |
| Angular uploaded to S3 | C |
| GitHub Actions pipelines | D |

---

## Corrections Applied to Existing Docs

| File | Was Wrong | Corrected |
|---|---|---|
| `DEPLOYMENT_AND_INFRASTRUCTURE.md` | Angular on Nginx on EC2, region ap-south-1, no ALB | Superseded by this plan — deleted |
| `environment.prod.ts` | `https://tinyurl.buffden.com/api` | `https://go.buffden.com/api` |
| `application-prod.yaml` SSM base-url | `https://tinyurl.buffden.com` | `https://go.buffden.com` |
| `ADR-006` | S3 + CloudFront for v2 | Moved to v1 |
| Cost estimate | ~$30/month | ~$54/month |
