# Low Level Design

> Detailed specifications for implementation. Read the HLD and ADRs before reading anything here.

---

## Documents

| Document | Purpose | Status |
| --- | --- | --- |
| [api-contract.md](api-contract.md) | All HTTP endpoints — request/response schemas, error codes, status codes | Done |
| module-structure.md | Java package layout, class responsibilities, dependency rules | Pending |
| cache-module.md | Cache-aside algorithm, TTL strategy, invalidation, circuit breaker (v2) | Pending |
| rate-limiting-module.md | Bucket4j token bucket config, Redis-backed limits, 429 headers (v2) | Pending |
