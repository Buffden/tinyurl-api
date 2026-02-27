# URL Shortener — POC 1 (Requirements-First Design)

> **Superseded** — This document is the original POC draft and is preserved for historical reference only.
> The authoritative baseline is [url-shortener-v1.md](v1/url-shortener-v1.md).
> Known divergences from frozen decisions: read/write ratio (80/20 here vs. 99:1), uptime target (99.99% here vs. 99.9%), and expired-link response (open here vs. HTTP 404 per ADR-004).
> Do not update this file — update the v1/v2 documents instead.

---

> Focus: define *what must exist* before choosing *how to build it*.

---

## 1) Problem Statement

Design a URL shortener system that:

- Accepts long URLs
- Generates short, unique aliases
- Redirects users to the original URL
- Maintains low latency and high availability under peak traffic

The system must prioritize redirect performance since redirects are perceived as part of normal page navigation.
---

## 2) Functional Requirements

- Users can submit a long URL and receive a short URL.
- Short URL redirects to the original URL.
- Support optional expiration time.
- Supports default link expiration after 180 days.
- Short URLs are immutable after creation.
- Redirect uses HTTP 301 (permanent) or 302 (temporary).

---

## 3) Non-Functional Requirements

| Category     | Requirement              | Target                           | Reason                                             |
| ------------ | ------------------------ | -------------------------------- | -------------------------------------------------- |
| Performance  | Redirect latency (P95)   | < 100 ms                         | realistic for a redirect experience.               |
| Performance  | Redirect latency (P99)   | < 200 ms                         |                                                    |
| Performance  | Peak redirect QPS        | 5000 QPS                         | moderate scale                                     |
| Performance  | Read/write ratio         | 80/20                            | redirect-heavy workload.                           |
| Availability | Uptime                   | 99.99 %                          |                                                    |
| Scalability  | URLs created per year    | 1 Million                        |                                                    |
| Scalability  | Total URLs after 3 years | 10 Milion                        | easily manageable in a relational DB with indexing |
| Durability   | Data loss tolerance      | 0% (persistent mapping required) |                                                    |

---

## 4) Assumptions

| Assumption                    | Value          |
| ----------------------------- | -------------- |
| Average URL length            | 100 characters |
| Users globally distributed    | Yes            |
| Authentication required       | No(v1)         |
| Custom aliases supported      | No(v1)         |
| URLs immutable after creation | Yes            |
| Analytics tracking            | No(v1)         |

---

## 5) Non-Goals (Explicitly De-Scoped)
Version 1:

- No analytics (v1).
- No authentication.
- No custom vanity URLs.
- No multi-region deployment.
- No click tracking.
- No abuse detection (beyond simple rate limiting).

> Modify as needed for your scope and version.

---

## 6) Minimum Components (What Must Exist)

- Client (browser).
- DNS Resolution.
- Load balancer (L4 or L7).
- Stateless application servers.
- Storage system (short -> original mapping) SQL.
- Optional in-memory cache for hot keys.

At 5K QPS, horizontal scaling is expected at app layer.

---

## 7) Data Model (Conceptual)

Mapping:

```
short_code -> original_url
```

Core fields:

- short_code (VARCHAR, PK)
- original_url (TEXT)
- created_at (TIMESTAMP)
- expires_at (TIMESTAMP, nullable)

Indexing:

- Primary index on short_code
- Index on expires_at with cleanup job exists.

Estimated storage:

10M rows × ~150 bytes ≈ ~1.5GB
Comfortably fits in single relational DB with indexing.

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

- Guarantees uniqueness.
- Short output length.
- Deterministic mapping.
- No collision handling required.
- Avoids random UUID length inflation.

This balances simplicity and scalability.

---

### Decision 2: Scaling Approach

Question: Vertical or horizontal scaling?

Chosen:
→ Horizontal scaling at application layer

Reason:

- Read-heavy workload (80/20).
- Stateless redirect logic.
- App instances can scale independently behind load balancer.
- DB initially vertically scaled, later read-replicated if needed.

Avoid premature sharding at this stage.

---

## 9) Failure Modes and Mitigation

| Component     | Failure            | Impact                 | Mitigation                   |
| ------------- | ------------------ | ---------------------- | ---------------------------- |
| DB            | Slow query         | Redirect latency spike | Proper indexing              |
| DB            | Down               | All redirects fail     | Replication + failover       |
| App           | Crash              | Partial 5xx errors     | Multiple instances behind LB |
| Load Balancer | Misroute           | Partial outage         | Health checks                |
| DNS           | Resolution failure | Full outage            | DNS caching + low TTL        |
| Cache         | Miss storm         | DB overload            | Rate limiting + TTL          |

Architect mindset:
Assume DB is first bottleneck.

---

## 10) Trade-Off Summary

What did you intentionally not optimize?

Intentionally NOT optimized:

- Strong global consistency
- Multi-region redundancy
- Analytics and click tracking
- Complex ID randomization
- Abuse detection systems

Reason:

This design prioritizes simplicity and performance over feature richness.

Over-engineering early increases operational complexity without immediate benefit. As a good Software Architect, one should never overengineer unless needed.

---

## 11) Open Questions and Possible Future Enhancements

- Should private links require authentication?
- Should links be editable?
- Should we support QR generation?
- Should we prevent malicious URL?
- Should expired links return 404, or custom message?