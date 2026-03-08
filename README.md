# TinyURL — Production-Grade URL Shortener

TinyURL is a single-region, production-oriented URL shortener system designed with scalability, reliability, and architectural evolution in mind.

This repository implements the service layer, while architectural evolution (v1 → v2 → future) is documented under `docs/architecture/`.

---

## Goals

This project demonstrates:

- Requirements-first system design
- Clean architectural evolution (v1 → v2)
- Production-ready service structure
- Scalable redirect-heavy workload handling
- Industrial Git hygiene and ADR usage

It is intentionally built as a realistic service, not a toy implementation.

---

## Version Overview

### v1 — Baseline Single-Region System

<table><tr>
<td valign="top">

- Base62 encoded short codes
- DB-backed ID generation
- Stateless application servers
- HTTP 301/302 support
- Optional expiration (default 180 days)
- No caching
- No analytics
- No authentication

Focus: correctness + simplicity.

</td>
<td><a href="diagrams/docs/architecture/00-baseline/v1/url-shortener-v1-hld.svg"><img src="diagrams/docs/architecture/00-baseline/v1/url-shortener-v1-hld.svg" width="100%" alt="v1 HLD"></a></td>
</tr></table>

### v2 — Scale & Abuse Resistance (Planned)

<table><tr>
<td valign="top">

- Redis cache (cache-aside)
- Negative caching
- Rate limiting (token bucket)
- Soft delete support
- Custom aliases (feature-flagged)
- Observability improvements

</td>
<td><a href="diagrams/docs/architecture/00-baseline/v2/url-shortener-v2-hld.svg"><img src="diagrams/docs/architecture/00-baseline/v2/url-shortener-v2-hld.svg" width="100%" alt="v2 HLD"></a></td>
</tr></table>

---
