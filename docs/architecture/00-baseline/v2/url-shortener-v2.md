# URL Shortener — v2 (Scale + Abuse Safety + Reliability)

> Focus: evolve v1 only when new constraints force new decisions (no “cool tech”).

---

## 1) Problem Statement

Evolve the v1 URL shortener into a system that:

- Sustains higher redirect traffic with stable tail latency
- Reduces database load on the hot redirect path
- Adds abuse resistance (enumeration, bot spikes, write flooding)
- Improves reliability with measurable SLOs and basic observability

Deployment target (v2): still **single-region**, hosted on `tinyurl.buffden.com`.

---

## 2) Functional Requirements

Carry over v1 requirements:

- Users can submit a long URL and receive a short URL.
- Short URL redirects to the original URL.
- Optional expiration time, default expiration **180 days**.
- Short URLs are immutable after creation.
- Redirect uses HTTP 301/302 based on expiry policy.

New in v2:

- Support **custom alias (optional feature flag)** with constraints:
  - Allowed characters: Base62 (`[0-9a-zA-Z]`)
  - Length: 4–32
  - Must be unique
  - Rate-limited (tighter than normal creates)
- Support **soft delete** (admin/internal) to block malicious links without losing auditability.

---

## 3) Non-Functional Requirements

New constraint introduced in v2: **higher QPS + hot-key traffic + abuse resistance**.

| Category     | Requirement                         | Target                        | Reason |
| ------------ | ----------------------------------- | ----------------------------- | ------ |
| Performance  | Redirect latency (P95)              | < 80 ms                       | Cache should improve typical redirect latency. |
| Performance  | Redirect latency (P99)              | < 150 ms                      | Reduce DB tail latency sensitivity. |
| Performance  | Avg redirect QPS                    | ~5,000 QPS                    | Scale beyond v1 baseline. |
| Performance  | Peak redirect QPS                   | 20,000 QPS                    | Handle spikes without collapsing DB. |
| Performance  | Cache hit ratio (redirect path)     | > 90%                         | Redirect path must mostly avoid DB. |
| Performance  | Read/write ratio                    | 90/10                         | At scale, reads dominate even more. |
| Availability | Uptime                              | 99.9%                         | Still single-region; improve stability via better ops. |
| Durability   | Data loss tolerance                 | 0%                            | Redirect mappings are durable and must not be lost. |
| Reliability  | Error rate (redirect 5xx)           | < 0.1%                        | Defines acceptable failure envelope. |
| Reliability  | Rate limiting effectiveness         | No sustained overload from single IP | Prevents bot spikes from dominating capacity. |

### Capacity Estimation (v2 intuition)

- Avg redirects/day: `5,000 × 86,400 ≈ 432M/day`
- Peak protection: 20K QPS bursts (minutes) must not melt DB.
- DB query rate goal: **< 10%** of redirect traffic (rest served from cache).

### Scaling Requirements (Explicit)

- **App autoscaling** based on CPU + P95 latency (keeps 20K QPS spikes stable).
- **Redis cluster mode** for shardable cache capacity and HA in single region.
- **Connection pooling tuning** at app and DB to prevent connection exhaustion under spikes.

---

## 4) Assumptions

| Assumption                             | Value |
| -------------------------------------- | ----- |
| Average URL length                     | ~100 chars avg; max 2,048 |
| Short code length                      | 6–8 chars Base62 |
| Traffic distribution                   | Highly skewed (Zipf-like): top 1–5% links dominate |
| Users globally distributed             | Yes (single region deployment) |
| Authentication required                | No (still v2) |
| Analytics tracking                     | No full analytics dashboard (still deferred) |
| Cache strategy                         | Cache-aside for redirects |
| Cache TTL policy                       | TTL = min(remaining expiry, max_ttl) |
| Negative caching                       | Enabled for unknown codes with small TTL |
| Abuse threat model                     | Enumeration + bot spikes + write flooding are expected |
| Consistency expectation                | Cache may serve slightly stale data within TTL window (acceptable since links are immutable) |

---

## 5) Non-Goals (Explicitly De-Scoped)

Version 2:

- No multi-region deployment.
- No user accounts / auth-gated private links.
- No advanced analytics pipeline (Kafka/Kinesis) or reporting dashboards.
- No malware scanning integrations (blocklists can be manual/admin in v2).
- No “editable links” feature (immutability preserved).

---

## 6) Minimum Components (What Must Exist)

Starting from v1 components, v2 requires additional components because of scale constraints:

- Client (browser)
- DNS resolution
- Load balancer
- Nginx (TLS termination + reverse proxy)
- Stateless application servers
- **Redis cache** (mandatory in v2 for redirect path)
- SQL database (PostgreSQL primary)
- Optional DB read replica (only if DB becomes bottleneck for cache misses/writes)
- Observability:
  - Metrics (RPS, latency percentiles, cache hit ratio, DB latency)
  - Logs (structured)
  - Tracing (optional; can be added later)

### Write Path (Create) — v2

1. Client sends `POST /api/urls` (with optional `alias` field for custom aliases) with URL and optional expiry.
2. Nginx applies rate limits and forwards to app.
3. App validates input, checks alias rules, and generates `short_code` if needed.
4. App writes mapping to primary DB (with soft delete fields defaulted).
5. App warms Redis cache for the new mapping.
6. App returns the short URL to the client.

---

## 7) Data Model (Conceptual)

Mapping:

```

short_code -> original_url

```

Core fields (v2 adds operational fields):

- short_code (VARCHAR(32), PK) *(supports custom aliases up to 32)*
- original_url (TEXT, NOT NULL)
- created_at (TIMESTAMP, NOT NULL)
- expires_at (TIMESTAMP, nullable)
- is_deleted (BOOLEAN, default false) *(soft delete / abuse blocking)*
- deleted_at (TIMESTAMP, nullable)
- created_ip (INET, nullable) *(optional; supports abuse detection / rate limiting audits)*

Indexing:

- Primary index on `short_code`
- Index on `expires_at` (supports cleanup jobs)
- Optional partial index: `WHERE is_deleted = false` if queries frequently filter deleted links

Expiration handling:

- Redirect returns 404/410 if expired or deleted (policy-defined)
- Cleanup job:
  - Periodic delete/archive of expired links OR
  - Keep expired links but mark inactive (depends on retention policy)

---

## 8) Key Design Decisions

### Decision 1: Caching Strategy (v2 introduces Redis)

Options considered:

- No cache (DB-only)
- Cache-aside (read-through manually)
- Read-through cache (cache loader)
- CDN-based caching only

Chosen approach:
→ **Redis cache-aside** on redirect path

Reason:

- Dominant traffic is redirects (reads), and traffic is skewed.
- Cache hit ratio > 90% dramatically reduces DB load and tail latency risk.
- Link immutability makes cache invalidation simple (TTL-based).
- Operationally standard and easy to reason about.

Cache policy:

- Key: `short_code`
- Value: `original_url + expires_at + is_deleted`
- TTL: `min(expires_at - now, MAX_TTL)`; if no expiry, use a long TTL (e.g., 7 days) with background refresh optional.

---

### Decision 2: Negative Caching

Problem:

- Random/bot traffic can hit many nonexistent codes and overload DB with misses.

Chosen approach:
→ Cache “not found” results for a short TTL (e.g., 30–120 seconds)

Reason:

- Cuts repeated miss traffic hitting DB.
- TTL stays low to reduce risk if code becomes valid shortly after.

---

### Decision 3: Rate Limiting Strategy

Options considered:

- Per-IP fixed window
- Token bucket / leaky bucket
- API gateway/WAF-only
- No rate limiting

Chosen approach:
→ Token bucket rate limiting at Nginx/app layer

Policies:

- Create endpoint: strict per-IP (e.g., 5 req/min burst 10)
- Redirect endpoint: light throttling only when suspicious (e.g., 200 req/sec/IP with burst)
- Custom alias endpoint: tighter limits than normal create

Reason:

- Prevents abusive traffic from consuming shared capacity.
- Keeps redirect path stable under attack/spike conditions.

---

## 9) Failure Modes and Mitigation

| Component | Failure | Impact | Mitigation |
| --- | --- | --- | --- |
| Redis | Down | Cache misses → DB overload risk | Circuit breaker: degrade gracefully (serve from DB with stricter rate limiting); Redis HA (replication) |
| Redis | Hot key | Single key overload | Local in-process caching for hottest keys (optional), request coalescing, TTL jitter |
| DB | Slow queries | Tail latency spike | Indexing, connection pool limits, query tuning |
| DB | Down | Redirects fail | Backups + failover plan; return 503 with retry headers |
| App | Thread exhaustion | Timeouts/5xx | Autoscale app, set timeouts, bulkheads |
| LB/Nginx | Misconfig | Partial/full outage | Health checks, config validation in CI |
| Rate limiter | Too strict | False throttling | Observe metrics, adjust thresholds, allowlists |
| Cache stampede | Many expirations at once | DB spike | TTL jitter + request coalescing for same key |

Architect mindset:
In v2, Redis adds performance but also a new failure domain—design for Redis failure explicitly.

---

## 10) Trade-Off Summary

Intentionally NOT optimized in v2:

- Multi-region availability and global latency optimization
- Enterprise authentication + private links
- Full analytics pipeline and dashboards
- Malware scanning integrations and automated takedown workflows
- Editable links / update destination feature

Reason:

v2 is about **making single-region robust and fast under load**.  
Adding enterprise/global features now would increase complexity faster than it increases learning/ROI.

---

## 11) Open Questions and Possible Future Enhancements

- Should custom aliases be enabled by default or gated by feature flag?
- Should redirects be guess-resistant (to reduce enumeration) by moving from sequential IDs to randomized IDs?
- What is the desired policy for expired links: 404 vs 410?
- Should we add a lightweight analytics counter (per link hit count) without a full analytics pipeline?
- Should we introduce a DB read replica now or only after measuring cache miss pressure?
- Do we need a WAF/CDN layer for bot traffic before going multi-region (v3)?