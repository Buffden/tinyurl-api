# Phase 2: Core API (v1)

## Objective

Implement the production-ready v1 API for URL creation and redirect with correct validation, expiry behavior, and error mapping.

## Depends On

- [Phase 1 Foundation](PHASE_1_FOUNDATION.md)

## Source References

- [LLD API Contract](../lld/api-contract.md)
- [LLD Module Structure](../lld/module-structure.md)
- [Use Cases v1](../use-cases/v1/)
- [Functional Requirements](../requirements/functional-requirements.md)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)
- [HLD Database v1](../hld/database/v1/database-design.md)

## In Scope

- `POST /api/urls`
- `GET /{short_code}`
- `GET /health` confirmation in API flow
- v1 expiry and error semantics
- unit and integration tests for core paths

## Out of Scope

- Redis cache behavior (v2)
- custom aliases (v2)
- cleanup scheduler (v2)

## Execution Steps

### Step 1: DTO and contract-first API model

References:

- [LLD API Contract](../lld/api-contract.md)
- [Use Case: Shorten URL v1](../use-cases/v1/UC-US-001-shorten-url.md)

Tasks:

- Create request/response DTOs and validation annotations
- Map contract error payload format

### Step 2: URL creation flow (`POST /api/urls`)

References:

- [Use Case: Shorten URL v1](../use-cases/v1/UC-US-001-shorten-url.md)
- [HLD DB Schema v1](../hld/database/v1/db-schema.md)

Tasks:

- Validate long URL and expiry input
- Generate sequence-backed short code (Base62)
- Persist mapping and return 201 response

### Step 3: Redirect flow (`GET /{short_code}`)

References:

- [Use Case: Redirect URL v1](../use-cases/v1/UC-US-002-redirect-url.md)
- [Use Case: Expire URL v1](../use-cases/v1/UC-US-003-expire-url.md)

Tasks:

- Lookup by short code
- Apply expiry checks and return 404/410 where applicable
- Return configured redirect status (301/302)

### Step 4: Repository and service layering

References:

- [LLD Module Structure](../lld/module-structure.md)
- [HLD System Design](../hld/system-design/system-design.md)

Tasks:

- Keep controllers thin, service-centric business rules
- Implement repository queries and data mapping cleanly

### Step 5: Testing and baseline performance checks

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

Tasks:

- Add unit tests for validation, encoding, and expiry logic
- Add integration tests for create and redirect flows
- Validate P95 baseline target under local load

## Deliverables

- Core v1 API implementation complete
- Test suite for happy path and key failure modes
- API behavior aligned with contract and use-cases

## Acceptance Criteria

- FR-001 to FR-005 satisfied
- API responses and errors match [LLD API Contract](../lld/api-contract.md)
- P95 response time target met locally for core endpoints
