# Phase 6: Custom Aliases (v2)

## Objective

Add feature-flagged custom alias support with strict validation, uniqueness guarantees, and correct conflict semantics.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 4 Cache Layer (v2)](PHASE_4_CACHE_LAYER_V2.md)
- [Phase 5 Rate Limiting (v2)](PHASE_5_RATE_LIMITING_V2.md)

## Source References

- [Use Case: Custom Alias v2](../use-cases/v2/UC-US-004-custom-alias.md)
- [LLD API Contract](../lld/api-contract.md)
- [Functional Requirements](../requirements/functional-requirements.md)
- [Rate Limiting Module](../lld/rate-limiting-module.md)

## In Scope

- custom alias field support in create flow
- feature flag gating for rollout safety
- alias validation, reserved words, and uniqueness checks

## Out of Scope

- alias marketplace/transfer features
- user-managed namespace hierarchies

## Execution Steps

### Step 1: Feature flag integration

References:

- [Use Case: Custom Alias v2](../use-cases/v2/UC-US-004-custom-alias.md)

Tasks:

- Add feature flag for alias behavior
- Ensure disabled path returns correct error

### Step 2: Validation rules and reserved namespace

References:

- [LLD API Contract](../lld/api-contract.md)

Tasks:

- Validate charset and length constraints
- Enforce reserved alias list rules

### Step 3: Uniqueness and conflict behavior

References:

- [Use Case: Custom Alias v2](../use-cases/v2/UC-US-004-custom-alias.md)

Tasks:

- Ensure alias uniqueness checks are race-safe
- Return `409 Conflict` for taken aliases

### Step 4: Rate and abuse controls for alias endpoint

References:

- [LLD Rate Limiting Module](../lld/rate-limiting-module.md)

Tasks:

- Apply stricter limits for alias creation path
- Validate 429 contract behavior for alias route

## Deliverables

- Custom alias create path available behind feature flag
- Validation and conflict semantics complete
- Tests for enabled/disabled and conflict scenarios

## Acceptance Criteria

- Alias creation works when feature flag is enabled
- Disabled state and invalid alias cases return correct errors
- Alias conflicts deterministically return 409
