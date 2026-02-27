# Architecture Baseline — URL Shortener

> This document is the anchor for the entire baseline. Read it first. Everything else (v1, v2, ADRs) is downstream of what is frozen here.

---

## Problem Statement

A URL shortener accepts a long URL and returns a short, human-shareable alias that redirects to the original. The system's primary job is redirection — it is not a metadata store, analytics platform, or user management system. Redirects are perceived as part of normal page navigation; any latency is user-visible. The system must therefore be optimised heavily for the read (redirect) path, while keeping the write (create) path simple and correct.

The deployment target is `tinyurl.buffden.com`, operating in a single region. v1 establishes a production-minimal baseline. v2 evolves it to handle higher scale and reliability requirements without changing the deployment model.

---

## System Boundary

### In scope

| Capability | Notes |
| --- | --- |
| URL creation | `POST /api/urls` — accepts a long URL, returns a short URL |
| URL redirect | `GET /{code}` — resolves the short code and issues an HTTP redirect |
| Expiration | Optional client-provided expiry; system default of 180 days if not set |
| Redirect semantics | HTTP 301 (no explicit expiry) or HTTP 302 (explicit expiry) |
| Basic abuse control | Per-IP rate limiting on the create endpoint (v1: Nginx-level) |
| Soft delete | Admin-only removal of a mapping without destroying the record (v2) |
| Custom aliases | User-provided short code with uniqueness enforcement (v2, feature-flagged) |

### Out of scope (all versions)

- User accounts and authentication
- Analytics dashboards, click tracking, referrer tracking
- Editable links (destination URL cannot change after creation)
- Multi-region deployment
- Malware / phishing URL scanning

---

## Constraints

Derived from [non-functional requirements](../../requirements/non-functional-requirements.md). These numbers govern every architecture decision.

### Performance

| Constraint | v1 | v2 |
| --- | --- | --- |
| Redirect latency P95 | < 100 ms | < 80 ms |
| Redirect latency P99 | < 200 ms | < 150 ms |
| Avg redirect QPS | ~1,000 | ~5,000 |
| Peak redirect QPS | 5,000 | 20,000 |
| Read / write ratio | 99:1 | 95:5 |
| Cache hit ratio (redirect path) | N/A | > 90% |

### Availability and Durability

| Constraint | Target |
| --- | --- |
| Uptime | 99.9% (both versions) |
| Data loss tolerance | 0% — losing a mapping breaks the link permanently |

### Scale

- ~1 million URLs created per year (early adoption)
- ~10 million total URLs after 3 years
- Storage estimate: 10M rows × ~150 bytes ≈ 1.5–3 GB — fits comfortably in a single relational DB

---

## Deployment Assumptions

These assumptions are not implementation choices — they are constraints that bound the design. If any changes, the requirements and architecture must be revisited.

| Assumption | Value |
| --- | --- |
| Deployment model | Single-region for all versions in scope |
| Authentication | None — all endpoints are public and anonymous |
| URL deduplication | None — the same long URL may produce multiple distinct short codes |
| Analytics | Not present in v1; not planned in v2 |
| Consistency model | Read-after-write within primary region, best-effort |
| Traffic distribution | Zipf-like — top 1–5% of links generate the majority of redirects |
| Short code length | 6–8 characters (Base62) |

---

## Frozen Decisions

These decisions are locked before v1 implementation begins. Changing them requires a new ADR.

| Decision | Value | ADR |
| --- | --- | --- |
| ID generation | Auto-increment DB sequence encoded in Base62 | [ADR-001](adr/ADR-001-id-generation-strategy.md) |
| Scaling approach | Horizontal at app layer; single primary DB | [ADR-002](adr/ADR-002-scaling-approach.md) |
| Redirect status code | 301 if no explicit expiry; 302 if explicit expiry set | [ADR-003](adr/ADR-003-redirect-status-code.md) |
| Expired / deleted link response | HTTP 410 (existed, now gone); HTTP 404 for unknown codes | [ADR-004](adr/ADR-004-expiration-policy.md) |

---

## Evolution Path

### v1 — Production-Minimal Baseline

Goal: ship the smallest correct system.

- Components: DNS → Load balancer → Nginx → App servers → PostgreSQL
- No cache required in v1; DB handles up to 5K QPS peak
- Rate limiting at Nginx level (coarse per-IP throttle on `POST /api/urls`)
- No soft delete required (optional `is_deleted` field, deferred to v2)

Key constraint driving v1 design: **redirect correctness and low operational surface area**.

See [v1/url-shortener-v1.md](v1/url-shortener-v1.md) and [v1/url-shortener-v1-hld.puml](v1/url-shortener-v1-hld.puml).

---

### v2 — Scale, Reliability, and Abuse Resistance

Goal: evolve v1 only where new constraints force new decisions.

New constraints driving v2:

- Peak QPS grows to 20,000 — DB alone cannot absorb the redirect path
- Skewed traffic (Zipf) makes caching effective and necessary
- Abuse vectors (bot enumeration, write flooding) become real at scale
- Reliability must be measurable (error rate SLO: < 0.1% 5xx on redirects)

What changes:

- Redis cache-aside added to the redirect path (> 90% cache hit target)
- Negative caching for unknown short codes (protects DB from miss storms)
- App autoscaling based on CPU + P95 latency
- Redis cluster mode for HA and shardable capacity
- Connection pool tuning at app and DB layers
- Custom alias endpoint (`POST /api/urls` with `alias` field, feature-flagged)
- Soft delete for admin abuse control

What does not change:

- Still single-region
- Still no authentication
- Still no analytics pipeline
- Data model extends but does not break v1 schema

See [v2/url-shortener-v2.md](v2/url-shortener-v2.md) and [v2/url-shortener-v2-hld.puml](v2/url-shortener-v2-hld.puml).

---

## Document Index

| Document | Purpose |
| --- | --- |
| [poc-requirements-baseline.md](poc-requirements-baseline.md) | Original requirements scratch-pad (superseded by structured requirements) |
| [v1/url-shortener-v1.md](v1/url-shortener-v1.md) | v1 design: components, data model, failure modes, trade-offs |
| [v2/url-shortener-v2.md](v2/url-shortener-v2.md) | v2 design: cache strategy, rate limiting, scaling decisions |
| [adr/ADR-001](adr/ADR-001-id-generation-strategy.md) | ID generation: Base62-encoded auto-increment sequence |
| [adr/ADR-002](adr/ADR-002-scaling-approach.md) | Scaling: horizontal app layer, single primary DB |
| [adr/ADR-003](adr/ADR-003-redirect-status-code.md) | Redirect status: 301 vs 302 based on expiry intent |
| [adr/ADR-004](adr/ADR-004-expiration-policy.md) | Expiration: 180-day default, HTTP 410 for expired/deleted, HTTP 404 for unknown |
