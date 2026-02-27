# URL Shortener — v1 (Requirements-First Design)

> Focus: define *what must exist* before choosing *how to build it*.

---

## 1) Problem Statement

Design a URL shortener system that:

- Accepts long URLs
- Generates short, unique aliases
- Redirects users to the original URL
- Maintains low latency and high availability under peak traffic

The system must prioritize redirect performance since redirects are perceived as part of normal page navigation.

Primary deployment target (v1): `tinyurl.buffden.com` (single region).

---

## 2) Functional Requirements

- Users can submit a long URL and receive a short URL.
- Short URL redirects to the original URL.
- Support optional expiration time (client can supply).
- Supports default link expiration after **180 days** if not provided.
- Short URLs are immutable after creation (no edits to destination in v1).
- Redirect uses **HTTP 301** (permanent) or **HTTP 302** (temporary):
  - If link is created without an explicit expiry → default to 301.
  - If link has an expiry (or is explicitly marked temporary) → use 302.

---

## 3) Non-Functional Requirements

> Note: v1 is single-region and intentionally minimal. Targets below reflect what’s realistic without multi-region.

| Category     | Requirement                | Target                           | Reason |
| ------------ | -------------------------- | -------------------------------- | ------ |
| Performance  | Redirect latency (P95)     | < 100 ms                         | Redirects must feel instant to users. |
| Performance  | Redirect latency (P99)     | < 200 ms                         | Tail latency should not degrade UX noticeably. |
| Performance  | Avg redirect QPS           | ~1,000 QPS                        | Capacity planning baseline (steady state). |
| Performance  | Peak redirect QPS          | 5,000 QPS                         | Moderate spikes; drives LB + app scaling. |
| Performance  | Read/write ratio (redirect:create) | ~99/1                        | URL shortener is overwhelmingly read-heavy; creates are infrequent relative to redirects. |
| Availability | Uptime                     | 99.9%                             | Realistic for single-region without multi-region. |
| Scalability  | URLs created per year      | 1 million                         | Reasonable for early adoption / portfolio product. |
| Scalability  | Total URLs after 3 years   | 10 million                        | Manageable in a single relational DB with indexing. |
| Durability   | Data loss tolerance        | 0% (persistent mapping required)  | Losing mappings breaks links permanently. |

### Capacity Estimation (Corrected)

- Avg redirect QPS: ~1,000
- Redirects/day: $1{,}000 \times 86{,}400 \approx 86.4\text{M}$ redirects/day
- Avg create QPS: ~5–10
- Creates/day: ~500K–864K creates/day (upper bound spike)
- Expected realistic creates/year: ~1M total URLs

Conclusion: Redirects dominate traffic; creation rate is several orders of magnitude lower.

---

## 4) Assumptions

| Assumption                    | Value |
| ----------------------------- | ----- |
| Average URL length            | ~100 characters (avg); max supported 2,048 characters |
| Short code length             | 6–8 characters (Base62) |
| Traffic distribution          | Skewed (Zipf-like): top 5% links generate majority of redirects |
| Users globally distributed    | Yes (but v1 runs in a single region) |
| Authentication required       | No (v1) |
| Custom aliases supported      | No (v1) |
| URLs immutable after creation | Yes |
| Analytics tracking            | No (v1) |
| Basic abuse control           | Basic per-IP rate limiting on create endpoint |
| Consistency expectation       | Read-after-write consistency within the primary region is “best effort” (no multi-region guarantees) |

---

## 5) Non-Goals (Explicitly De-Scoped)

Version 1:

- No analytics (click counts, referrers, geo, dashboards).
- No authentication / user accounts.
- No custom vanity URLs / custom aliases.
- No multi-region deployment.
- No click tracking pipeline.
- No advanced abuse detection (beyond simple rate limiting).
- No content scanning / malicious URL detection in v1.

Reason: keep v1 small, shippable, and focused on redirect correctness + low latency.

---

## 6) Minimum Components (What Must Exist)

- Client (browser).
- DNS resolution (entry to `tinyurl.buffden.com`).
- Load balancer (L4 or L7).
- Nginx (TLS termination + reverse proxy).
- Stateless application servers (redirect + create logic).
- Storage system (SQL) for `short_code → original_url` mapping.
- Optional in-memory cache for hot keys (NOT required in v1; evaluate after measuring DB load).

At 5K QPS peak, horizontal scaling is expected at the application layer.

### Write Path (Create) — v1

1. Client sends `POST /api/urls` with original URL and optional expiry.
2. Load balancer routes to an app instance via Nginx.
3. App validates input and generates `short_code` (DB-backed sequence + Base62).
4. App writes mapping to the primary DB.
5. App returns the short URL to the client.

---

## 7) Data Model (Conceptual)

Mapping:

```

short_code -> original_url

```

Core fields:

- short_code (VARCHAR(8), PK)
- original_url (TEXT, NOT NULL)
- created_at (TIMESTAMP, NOT NULL)
- expires_at (TIMESTAMP, nullable)
- is_deleted (BOOLEAN, default false)  *(optional; can be deferred to v2)*

Indexing:

- Primary index on `short_code`
- Index on `expires_at` (only if running expiration cleanup job)
- Optional: partial index on `(expires_at)` where `expires_at IS NOT NULL`

Expiration handling (v1):

- On redirect: if `expires_at < now()` → treat as not found (404) or gone (410) per policy.
- Cleanup job is optional in v1; can be added in v2 if table growth or operational cost increases.

Estimated storage:

- 10M rows → typically ~2–3 GB including index overhead (safe for Postgres with proper maintenance).

---

## 8) Key Design Decisions

### Decision 1: ID Generation Strategy

Options considered:

- Auto-increment ID
- UUID
- Base62 encoding
- Hash-based (SHA)

Chosen approach:
→ Auto-increment numeric ID encoded in Base62

Reason:

- Guarantees uniqueness (no collisions).
- Produces short codes (compact URLs).
- Deterministic mapping; no collision resolution logic.
- Operationally simple (DB sequence as source of truth).
- Avoids long UUID strings.

Assumption:
- ID generation is centralized (primary DB sequence). If we scale writes across regions later, this will be revisited.

---

### Decision 2: Scaling Approach

Question: Vertical or horizontal scaling?

Chosen:
→ Horizontal scaling at application layer

Reason:

- Read-heavy workload (80/20).
- Redirect path is stateless (lookup + redirect).
- App instances can scale independently behind the load balancer.
- DB starts as a single primary (vertical scaling + tuning); introduce read replicas later if needed.

Avoid premature sharding at this stage.

---

## 9) Failure Modes and Mitigation

| Component     | Failure             | Impact                 | Mitigation |
| ------------- | ------------------- | ---------------------- | ---------- |
| DB            | Slow query / lock    | Redirect latency spike | Proper indexing, query tuning, connection pool limits |
| DB            | Down                | All redirects fail     | Automated backups, failover plan (single-region), clear 503 response |
| App           | Crash               | Partial 5xx errors     | Multiple instances behind LB, health checks |
| Load Balancer | Misroute / unhealthy | Partial outage         | Health checks + autoscaling policies |
| DNS           | Resolution failure  | Full outage            | Correct records + sensible TTL; rely on resolver caching |
| Nginx/TLS     | Cert expired        | Full outage (HTTPS)    | Automated certificate renewal (Let’s Encrypt) + monitoring |
| Cache (if any)| Miss storm          | DB overload            | (v2) cache-aside + TTL + stampede protection |

Architect mindset:
Assume DB and TLS/cert management are the first real operational risks.

---

## 10) Trade-Off Summary

Intentionally NOT optimized in v1:

- Strong global consistency
- Multi-region redundancy
- Analytics and click tracking
- Guess-resistant short codes / anti-enumeration
- Advanced abuse detection systems
- Malware/phishing detection

Reason:

This design prioritizes correctness, simplicity, and low operational overhead.

Over-engineering early increases complexity and failure surface area without clear ROI. As a good Software Architect, one should never overengineer unless needed.

---

## 11) Open Questions and Possible Future Enhancements

- Should private links require authentication (v3)?
- Should links be editable (update destination) with audit trail (v3)?
- Should we support QR generation (v2/v3)?
- Should we prevent malicious URLs (v3) via blocklists or scanning?
- Should expired links return 404 (Not Found) or 410 (Gone)?
- Should we support custom aliases (v2)?
- Do we need guess-resistant IDs to prevent enumeration (v2)?