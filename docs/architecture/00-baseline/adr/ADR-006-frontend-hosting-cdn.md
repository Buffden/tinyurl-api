# ADR-006: Frontend Hosting on CDN + Object Storage

**Status**: Accepted (amended)
**Date**: March 2026
**Amended**: March 2026 — domain strategy section added after discrepancy found between original implementation notes and system-design.md
**Deciders**: Architecture review

---

## Context

The frontend is an Angular SPA with client-side routing. Earlier deployment discussion
considered serving the frontend from Nginx inside the same Docker Compose stack as the
backend.

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
- Serve via a dedicated CloudFront distribution (`tinyurl.buffden.com`)
- Keep API and short-code redirects on a separate CloudFront distribution (`go.buffden.com`) backed by Nginx on EC2
- Keep Redis (if used) inside backend Docker stack; frontend deployment does not restart Redis

This is a **two-distribution, two-subdomain** model. See the Domain Strategy section below
for the full rationale and the alternative that was considered.

---

## Domain Strategy

This is the most nuanced routing decision in this ADR. Two models were considered.

### Option A — Single domain, single CloudFront distribution (rejected for v1)

`go.buffden.com` → one CloudFront distribution with two origins:

```plaintext
/api/*          → origin: VPS nginx (backend)
/* (default)    → origin: S3 (SPA)
```

**Industry context:** This is what large URL shorteners do. Bitly, TinyURL, and Short.io
all serve both their web app and their redirects from the same root domain. It is the
most user-friendly model — users never see a subdomain.

| Pros | Cons |
| --- | --- |
| Single clean domain for everything | CloudFront path behaviors use prefix/wildcard only — no regex |
| No CORS headers needed (same origin) | Short codes (`/abc123`) are indistinguishable from SPA routes (`/about`) at the CloudFront layer |
| Fewer DNS records and ACM certificates | Requires Lambda@Edge to apply regex routing at the edge — adds cost, cold-start latency, and operational complexity |
| More "industry standard" UX | Without Lambda@Edge, short codes must either carry a path prefix (e.g. `/r/abc123`) or be resolved client-side — both break ADR-003 (server-side 301/302) |

The core blocker is the short-code routing problem. CloudFront cannot distinguish
`/abc123` (a redirect) from `/about` (an Angular route) without a Lambda@Edge function
or a URL scheme change. Neither is acceptable for v1.

### Option B — Two subdomains, two CloudFront distributions (accepted)

```plaintext
go.buffden.com      → CloudFront (API dist) → VPS nginx → Spring Boot
tinyurl.buffden.com  → CloudFront (SPA dist) → S3 bucket
```

| Pros | Cons |
| --- | --- |
| Short codes route natively — `go.buffden.com/abc123` goes to nginx with no special logic | SPA lives at `app.` subdomain, not the root domain |
| Preserves server-side 301/302 redirects exactly as specified in ADR-003 | CORS configuration required on Spring Boot (`/api/**` must allow `tinyurl.buffden.com`) |
| No Lambda@Edge — simpler ops | Two ACM certificates to manage (one per distribution) |
| Complete operational separation — frontend and backend failures are isolated | Two CloudFront distributions to configure and monitor |
| Independent cache invalidation per concern | |

### Decision: Option B for v1

Option B is chosen for v1 because:

1. **ADR-003 compliance.** Server-side 301/302 redirects are a hard requirement.
   Option A breaks this without Lambda@Edge or a URL scheme change.

2. **Operational simplicity.** Lambda@Edge introduces deployment complexity, versioning,
   and IAM permissions that are out of scope for v1.

3. **Short URL cleanliness.** The URL users share is `go.buffden.com/abc123` — clean,
   no prefix. The `app.` subdomain only affects the web UI, not the shareable links.

4. **Upgrade path exists.** Option A with Lambda@Edge can be adopted in a later version
   once traffic justifies the operational overhead. Switching only requires adding a
   Lambda@Edge function and updating DNS — no backend changes.

---

## Rationale

1. **Better separation of concerns** — Frontend delivery and backend runtime are decoupled.
   Frontend-only releases do not restart application or cache containers.

2. **Lower operational overhead** — No frontend process to run, patch, or scale on EC2.
   CDN and object storage reduce VM-level maintenance burden.

3. **Better performance at scale** — Static assets are cached at CloudFront edge locations.
   Reduced origin load and lower median latency for global users.

4. **Safer deployment model** — Faster rollback by switching to previous asset version in S3.
   Smaller blast radius when frontend changes fail.

---

## Cost Notes

Cost is mostly usage-based and typically low at early traffic:

- S3 storage: low monthly baseline for static bundles
- CloudFront: request and egress based (main cost driver as traffic grows)
- Route53 hosted zone and DNS queries
- ACM certificates for both CloudFront distributions: no additional certificate fee

For low to moderate traffic, this is usually cheaper than keeping separate always-on
compute for frontend serving.

---

## Consequences

Positive:

- Frontend can be deployed independently from backend
- Redis continuity is preserved during frontend deploys
- Better static asset caching (`index.html` short TTL, hashed JS/CSS long TTL)
- Easier blue/green style rollback for UI assets
- Short-code redirects remain fully server-side (ADR-003 preserved)

Negative / Tradeoffs:

- SPA is served from `tinyurl.buffden.com`, not the root domain
- CORS must be configured on Spring Boot for `https://tinyurl.buffden.com`
- Two CloudFront distributions and two ACM certificates to manage
- Cache invalidation discipline required after every frontend deploy

---

## Alternatives Considered

| Option | Why Rejected |
| --- | --- |
| Single domain with Lambda@Edge | Correct long-term approach but Lambda@Edge adds deployment/ops complexity out of scope for v1. Upgrade path for v2+. |
| Single domain without Lambda@Edge | Requires short-code URL prefix (`/r/abc123`) or client-side redirect — both violate ADR-003. |
| Frontend from Nginx container on EC2 | Couples UI deploy lifecycle to backend host and runtime; adds avoidable operational coupling. |
| Separate frontend VM/container service | Works, but higher steady-state cost and more patching/ops than static hosting. |
| Full SSR runtime for all pages | Not required for current product scope; higher complexity than needed in v1/v2. |

---

## Implementation Notes

**DNS records:**

| Type  | Name            | Target                             |
| :---- | :-------------- | :--------------------------------- |
| CNAME | `tinyurl`       | CloudFront API distribution domain |
| CNAME | `app.tinyurl`   | CloudFront SPA distribution domain |

**CloudFront — API distribution (`go.buffden.com`):**

- Origin: VPS nginx (custom origin)
- Behaviors: all paths forwarded to nginx; nginx handles `/api/*` and `/{shortCode}` routing

**CloudFront — SPA distribution (`tinyurl.buffden.com`):**

- Origin: S3 bucket
- Default behavior: S3 origin
- Error response mapping for SPA client-side routes:
  - 403 → `/index.html` (200)
  - 404 → `/index.html` (200)

**Frontend artifacts cache-control:**

- `index.html`: `max-age=60` (short TTL — entry point changes on every deploy)
- Hashed JS/CSS bundles: `max-age=31536000, immutable` (1 year — content-addressed)

**CORS on Spring Boot:**

- Allow origin: `https://tinyurl.buffden.com`
- Allow methods: `GET`, `POST`
- Apply to: `/api/**` only

**Future upgrade path to Option A (single domain):**

- Add a Lambda@Edge function on the API distribution's viewer-request event
- Regex-match `^/[0-9a-zA-Z_-]{4,32}$` → forward to VPS origin
- All other non-API paths → forward to S3 origin
- Remove `tinyurl.buffden.com` distribution and update Angular `apiBaseUrl` to relative path
