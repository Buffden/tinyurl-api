# ADR-006: Frontend Hosting on CDN + Object Storage

**Status**: Accepted
**Date**: March 2026
**Deciders**: Architecture review

---

## Context

The frontend is an Angular SPA with client-side routing. Earlier deployment discussion considered serving the frontend from Nginx inside the same Docker Compose stack as the backend.

Current constraints and goals:

- Keep operations simple and low cost in v1 and v2
- Keep PostgreSQL on RDS as the primary database
- Allow independent frontend deployment without touching backend and Redis containers
- Support deep links and browser refresh for SPA routes
- Improve global latency and cache efficiency for static assets

---

## Decision

Host the Angular SPA as static assets in object storage and serve it through a CDN:

- Build frontend in CI (`ng build`)
- Upload build artifacts to S3
- Serve via CloudFront
- Keep API on EC2 Docker stack behind Nginx
- Keep Redis (if used) inside backend Docker stack; frontend deployment does not restart Redis

Routing behavior for SPA support:

- Configure CloudFront to return `/index.html` for 403/404 on frontend routes
- Keep `/api/*` routed to backend origin and never rewritten to `/index.html`

---

## Rationale

1. Better separation of concerns
- Frontend delivery and backend runtime are decoupled.
- Frontend-only release does not restart application or cache containers.

2. Lower operational overhead
- No frontend process to run, patch, or scale on EC2.
- CDN and object storage reduce VM-level maintenance burden.

3. Better performance at scale
- Static assets are cached at edge locations.
- Reduced origin load and lower median latency for global users.

4. Safer deployment model
- Faster rollback by switching to previous asset version.
- Smaller blast radius when frontend changes fail.

---

## Cost Notes

Cost is mostly usage-based and typically low at early traffic:

- S3 storage: low monthly baseline for static bundles
- CloudFront: request and egress based (main cost driver as traffic grows)
- Route53 hosted zone and DNS queries
- ACM certificate for CloudFront: no additional certificate fee

For low to moderate traffic, this is usually cheaper than keeping separate always-on compute for frontend serving.

---

## Consequences

Positive:

- Frontend can be deployed independently from backend
- Redis continuity is preserved during frontend deploys
- Better static asset caching strategy (`index.html` short cache, hashed assets long cache)
- Easier blue/green style rollback for UI assets

Negative / Tradeoffs:

- Requires CDN behavior configuration for SPA fallback routes
- Cache invalidation discipline is required after deploy
- SSR features are not available in pure static SPA mode
- Two-origin architecture (frontend origin + API origin) requires clear routing rules

---

## Alternatives Considered

| Option | Why Rejected |
| --- | --- |
| Frontend from Nginx container on EC2 | Couples UI deploy lifecycle to backend host and runtime; adds avoidable operational coupling |
| Separate frontend VM/container service | Works, but higher steady-state cost and more patching/ops than static hosting |
| Full SSR runtime for all pages | Not required for current product scope; higher complexity than needed in v1/v2 |

---

## Implementation Notes

- Frontend artifacts: cache-control with long TTL for hashed JS/CSS, short TTL for `index.html`
- CloudFront behaviors:
  - Default behavior: S3 origin (frontend)
  - `/api/*`: backend origin
- Error response mapping for SPA routes:
  - 403 -> `/index.html` (200)
  - 404 -> `/index.html` (200)
