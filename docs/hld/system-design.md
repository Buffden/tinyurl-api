# System Design

> High-level architecture overview for TinyURL across versions.

---

## 1) System Overview

TinyURL is a single-region URL shortener deployed at `tinyurl.buffden.com`. The system accepts long URLs, generates unique short codes, and redirects users to the original URL with low latency.

The architecture follows a stateless application tier backed by a relational database, with caching introduced in v2 to absorb redirect traffic.

---

## 2) Component Architecture

### v1 Components

```
User → DNS → Load Balancer → Nginx (TLS) → App Server(s) → PostgreSQL
```

| Component | Responsibility |
|---|---|
| DNS | Resolves `tinyurl.buffden.com` to the load balancer. |
| Load Balancer (L4/L7) | Distributes traffic across application instances. Health checks. |
| Nginx | TLS termination, reverse proxy, static rate limiting (v2). |
| Application Server | Application servers hold no in-memory state required for correctness. Any instance can handle any request. Horizontally scalable. |
| PostgreSQL | Primary data store. Stores `short_code → original_url` mappings. Single primary. |

### v2 Additions

```
User → DNS → LB → Nginx (TLS + Rate Limiting) → App → Redis (Cache) → PostgreSQL
                                                      ↘ Metrics + Logs
```

| Component | Responsibility |
|---|---|
| Redis | Cache-aside for redirect path. Negative caching for unknown codes. |
| Metrics | RPS, latency percentiles, cache hit ratio, DB latency. |
| Structured Logs | Request tracing and operational debugging. |

---

## 3) Data Flow

### Write Path (Create Short URL)

1. Client sends `POST /create` with original URL and optional expiry.
2. Request passes through LB → Nginx → App.
3. App validates input (URL format, length).
4. App generates `short_code` via DB sequence + Base62 encoding.
5. App writes mapping to PostgreSQL.
6. (v2) App warms Redis cache for the new mapping.
7. App returns the short URL to the client.

### Read Path (Redirect)

1. Client accesses `GET /<short_code>`.
2. Request passes through LB → Nginx → App.
3. (v2) App checks Redis cache first.
4. On cache miss: App queries PostgreSQL by `short_code`.
5. App checks expiry and deletion status.
6. If valid: redirect with HTTP 301 or 302 (based on expiry policy).
7. If expired/deleted/not found: return HTTP 404 or 410.
8. (v2) App populates Redis cache on miss (cache-aside pattern).

---

## 4) Failure Domains

| Component | Failure Impact | Mitigation |
|---|---|---|
| PostgreSQL down | All redirects and creates fail | Automated backups, failover plan, 503 response |
| Redis down (v2) | Cache misses → all traffic hits DB | Circuit breaker, degrade to DB-only mode |
| App crash | Partial 5xx errors | Multiple instances, health checks, auto-restart |
| Nginx/TLS cert expired | Full HTTPS outage | Automated cert renewal (Let's Encrypt), monitoring |
| DNS resolution failure | Full outage | Correct records, sensible TTL, resolver caching |
| LB misroute | Partial outage | Health checks, autoscaling policies |

---

## 5) Scaling Strategy

### v1
- **App tier**: Horizontal scaling behind load balancer.
- **DB tier**: Single primary, vertically scaled. Adequate for 1K avg QPS.
- **No caching**: At 5K QPS peak, a properly indexed PostgreSQL instance (in-memory index + connection pooling) can sustain redirect lookups without requiring a cache tier.

### v2
- **App tier**: Autoscaling based on CPU + P95 latency.
- **Cache tier**: Redis cluster mode for HA and shardable capacity.
- **DB tier**: Single primary with connection pool tuning. Read replicas considered only under measured cache miss pressure.

See [ADR-002: Scaling Approach](../architecture/00-baseline/adr/ADR-002-scaling-approach.md) for the full decision record.

---

## 6) Diagrams

- v1 HLD: [`../architecture/00-baseline/v1/url-shortener-v1-hld.puml`](../architecture/00-baseline/v1/url-shortener-v1-hld.puml)
- v2 HLD: [`../architecture/00-baseline/v2/url-shortener-v2-hld.puml`](../architecture/00-baseline/v2/url-shortener-v2-hld.puml)
- System overview diagram: [`../diagrams/architecture/system-overview.puml`](../diagrams/architecture/system-overview.puml)
