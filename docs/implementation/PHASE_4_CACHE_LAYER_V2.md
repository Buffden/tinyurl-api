# Phase 4: Cache Layer (v2)

## Objective

Introduce Redis cache-aside on the redirect path to reduce database load and improve read latency.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 3 Observability](PHASE_3_OBSERVABILITY.md)

## Source References

- [LLD Cache Module](../lld/cache-module.md)
- [Use Cases v2](../use-cases/v2/)
- [HLD Database v2](../hld/database/v2/database-design.md)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

## In Scope

- Redis integration
- cache-aside reads on redirect path
- negative caching and TTL policy
- cache failure fallback to DB

## Out of Scope

- custom alias logic
- rate-limiting logic

## Execution Steps

### Step 1: Redis integration and configuration

References:

- [LLD Cache Module](../lld/cache-module.md)
- [HLD Database v2](../hld/database/v2/database-design.md)

Tasks:

- Add Redis client configuration and connection management
- Add environment-driven timeout and retry defaults

### Step 2: Cache-aside read flow

References:

- [Use Case: Redirect URL v2](../use-cases/v2/UC-US-002-redirect-url.md)

Tasks:

- Lookup short code in cache first
- On miss, read DB and populate cache

### Step 3: Negative caching and TTL policy

References:

- [LLD Cache Module](../lld/cache-module.md)

Tasks:

- Store null/missing markers with short TTL
- Use TTL + jitter for stable eviction patterns

### Step 4: Resilience behavior

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

Tasks:

- Implement graceful fallback to DB on cache outage
- Track cache hit/miss/error metrics

## Deliverables

- Redirect flow supports cache hit and miss paths
- Negative caching active for unknown codes
- Observability includes cache performance metrics

## Acceptance Criteria

- Cache hit ratio target achievable under load
- Redis outage does not break redirect correctness
- v1 behavior remains functionally correct when cache is bypassed
