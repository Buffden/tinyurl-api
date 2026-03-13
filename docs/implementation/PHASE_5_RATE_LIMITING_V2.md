# Phase 5: Rate Limiting (v2)

## Objective

Add layered rate limiting to protect create and redirect APIs from abuse while preserving availability.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 4 Cache Layer (v2)](PHASE_4_CACHE_LAYER_V2.md)

## Source References

- [LLD Rate Limiting Module](../lld/rate-limiting-module.md)
- [Use Cases v2](../use-cases/v2/)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)
- [Threat Model](../security/threat-model.md)

## In Scope

- Nginx coarse-grained limit rules
- app-level token-bucket rules
- standardized 429 responses and headers

## Out of Scope

- user billing/quota system
- account-tier monetization policy

## Execution Steps

### Step 1: Edge/proxy limits

References:

- [LLD Rate Limiting Module](../lld/rate-limiting-module.md)

Tasks:

- Configure endpoint-specific Nginx rate rules
- Exclude health endpoint from rate limiting

### Step 2: App-layer token bucket limits

References:

- [LLD Rate Limiting Module](../lld/rate-limiting-module.md)
- [Use Cases v2](../use-cases/v2/)

Tasks:

- Implement per-IP/per-endpoint buckets
- Use Redis-backed state for distributed consistency

### Step 3: Client-facing error semantics

References:

- [LLD API Contract](../lld/api-contract.md)

Tasks:

- Return `429 Too Many Requests`
- Include `Retry-After` and limit headers

### Step 4: Validation under burst traffic

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

Tasks:

- Add integration tests for threshold crossings
- Verify normal traffic remains unaffected

## Deliverables

- Multi-layer rate limiting active
- 429 behavior contract-compliant
- Monitoring includes limit-hit metrics

## Acceptance Criteria

- Abusive request patterns are throttled
- Health endpoint remains accessible
- Legitimate traffic success rates remain acceptable
