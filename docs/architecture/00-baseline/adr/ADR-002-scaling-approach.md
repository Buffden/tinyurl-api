# ADR-002: Scaling Approach

**Status**: Accepted
**Date**: February 2026
**Deciders**: Architecture review

---

## Context

The system is read-heavy (99:1 read/write ratio in v1, 95:5 in v2). The redirect path is stateless — every request is an independent DB lookup followed by an HTTP redirect. Scaling constraints:

- v1 target: 1K avg / 5K peak redirect QPS
- v2 target: 5K avg / 20K peak redirect QPS
- Single-region deployment for both versions
- Operational simplicity is a first-class constraint

---

## Decision

**v1**: Scale application servers **horizontally** behind a load balancer. Keep the database as a **single PostgreSQL primary** (vertical scaling + indexing). No cache required.

**v2**: Add **Redis cache-aside** on the redirect path (> 90% cache hit target) to absorb the 20K QPS peak without overloading the DB. Enable app autoscaling on CPU + P95 latency. Add a DB read replica only if cache miss pressure on the primary becomes measurable.

---

## Consequences

- Application instances must be **fully stateless** — no in-process session or shared memory.
- Load balancer and health checks are required infrastructure from v1.
- The trigger for adding read replicas is a **measured metric**, not an upfront decision.
- v2 introduces Redis as a new operational failure domain; the redirect path must degrade gracefully when Redis is unavailable (fall back to DB, apply stricter rate limiting).

---

## Alternatives Considered

| Option | Why Rejected |
| --- | --- |
| Vertical scaling only | Hard ceiling on both app and DB; single point of failure at every layer |
| Horizontal scaling at all layers from v1 | Adds sharding, distributed cache, and replica coordination before load data exists — premature complexity |
