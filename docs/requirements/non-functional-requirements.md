# Non-Functional Requirements

> Defines how the system must perform. Requirements are versioned to show evolution.

---

## Performance

| ID | Requirement | v1 Target | v2 Target | Reason |
|---|---|---|---|---|
| NFR-P01 | Redirect latency (P95) | < 100 ms | < 80 ms | Redirects must feel instant to users. |
| NFR-P02 | Redirect latency (P99) | < 200 ms | < 150 ms | Tail latency should not degrade UX. |
| NFR-P03 | Avg redirect QPS | ~1,000 | ~5,000 | Capacity planning baseline. |
| NFR-P04 | Peak redirect QPS | 5,000 | 20,000 | Burst handling without DB collapse. |
| NFR-P05 | Read/write ratio | 99:1 | 95:5 | URL shorteners are overwhelmingly read-heavy even at scale. |
| NFR-P06 | Cache hit ratio | N/A | > 90% | Redirect path must mostly avoid DB (v2). |

---

## Availability

| ID | Requirement | v1 Target | v2 Target | Reason |
|---|---|---|---|---|
| NFR-A01 | Uptime | 99.9% | 99.9% | Realistic for single-region deployment. |

---

## Durability

| ID | Requirement | Target | Reason |
|---|---|---|---|
| NFR-D01 | Data loss tolerance | 0% | Losing URL mappings breaks links permanently. |

---

## Reliability

| ID | Requirement | v1 Target | v2 Target | Reason |
| --- | --- | --- | --- | --- |
| NFR-R01 | Error rate (redirect 5xx) | N/A | < 0.1% | Defines acceptable failure envelope; monitored from v2 onward. |
| NFR-R02 | Rate limiting (create endpoint) | Basic per-IP throttle (Nginx-level, see FR-008) | No sustained overload from single IP across all endpoints | Prevents bot spikes from consuming capacity. |

---

## Scalability

| ID | Requirement | Target | Reason |
|---|---|---|---|
| NFR-S01 | URLs created per year | 1 million | Reasonable for early adoption. |
| NFR-S02 | Total URLs after 3 years | 10 million | Manageable in a single relational DB with indexing. |

---

## Capacity Estimation

### v1

- Avg redirect QPS: ~1,000
- Redirects/day: 1,000 x 86,400 = ~86.4M redirects/day
- Avg create QPS: ~5-10
- Storage: 10M rows x ~150 bytes = ~1.5-3 GB (comfortable for PostgreSQL)

### v2

- Avg redirect QPS: ~5,000
- Redirects/day: 5,000 x 86,400 = ~432M/day
- Peak: 20K QPS bursts must not overload DB
- DB query rate goal: < 10% of redirect traffic (rest from cache)

---

## Scaling Requirements (v2)

- **App autoscaling**: Based on CPU + P95 latency to handle 20K QPS spikes.
- **Redis cluster mode**: Shardable cache capacity and HA within single region.
- **Connection pooling**: Tuned at app and DB layers to prevent exhaustion under spikes.
