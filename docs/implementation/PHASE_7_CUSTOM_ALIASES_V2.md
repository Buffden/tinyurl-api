# Phase 7: Custom Aliases (v2)

## Objective

Add feature-flagged custom alias support with strict validation, uniqueness guarantees, and correct conflict semantics.

## MVP-Only Execution Rule

- For the current timeline, implement only content before `## Optional (Post-MVP)`.
- Treat `## Optional (Post-MVP)` items as backlog and do not block release.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 5 Cache Layer (v2)](PHASE_5_CACHE_LAYER_V2.md)
- [Phase 6 Rate Limiting (v2)](PHASE_6_RATE_LIMITING_V2.md)

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
- Stricter rate limits (2/min) enforced for alias creation path
- Existing aliases remain unaffected when custom alias is not supplied

---

## Part 2: Implementation Deep Dive

### Feature Flag Architecture

**Spring Boot Conditional Property Pattern:**

**Feature Flag Configuration Class:**
Add a `FeatureFlags` configuration class bound to a `features` prefix in `application.yaml`. It exposes a single boolean `customAliasEnabled` property. Inject this class wherever the feature gate needs to be checked. The flag defaults to `false` and is overridden to `true` in staging/production config when ready to roll out.

**API Controller with Feature Flag Guard:**
In the `POST /api/urls` controller, check `featureFlags.isCustomAliasEnabled()` before processing the `customAlias` field from the request body. If the flag is off and a `customAlias` value is present in the request, return a `400 FEATURE_UNAVAILABLE` error immediately. If the flag is on, pass the alias through to the service layer for validation and persistence.

**Fallback Behavior When Disabled:**
When the feature flag is off, the `customAlias` field is silently ignored if absent or explicitly rejected with `400 FEATURE_UNAVAILABLE` if provided. The system always falls back to generating a Base62 short code via the sequence-backed encoder, ensuring v1 behavior is fully preserved.

---

### Custom Alias Validation Rules

**Validation Constraints (RFC-adherent):**

| Constraint | Value | Rationale |
| --- | --- | --- |
| Minimum length | 4 characters | Prevents single-char typos, domain-grabbing |
| Maximum length | 32 characters | Reasonable API contract, memorable for users |
| Character set | [a-zA-Z0-9_-] (Base62 + hyphen/underscore) | URL-safe, human-readable |
| Reserved words | 200+ (see below) | Prevent namespace conflicts |
| Reserved prefixes | admin, api, app, internal | Block internal routes |

**Validator Implementation:**
Implement a `CustomAliasValidator` class that checks: length between 4 and 32 characters, characters match `[a-zA-Z0-9_-]` only, the alias does not start with a reserved prefix (`admin`, `api`, `app`, `internal`), and the alias is not in a hardcoded reserved-word list (e.g., `help`, `login`, `status`, `about`). Return a specific error code (`INVALID_ALIAS`) with a descriptive message on any failure. Wire this as a Spring `@Component` called from the service layer before any database interaction.

---

### Race-Safe Uniqueness Validation

**Database Schema (with UNIQUE constraint):**
Add a `UNIQUE` constraint on the `short_code` column in the `url_mappings` table via a new Flyway migration. This is the authoritative conflict guard — the application-layer check is a fast-path optimization, but the database constraint is what guarantees correctness under concurrent inserts.

**Conflict Detection at Application Layer:**
Before inserting, query the repository for an existing record with the requested alias. If found, throw a `AliasConflictException` immediately without attempting the insert. This avoids unnecessary write attempts and DB constraint violation exceptions on the happy path. Under concurrent load, a race between two requests for the same alias is caught by the database `UNIQUE` constraint and must also be handled.

**Exception Handler for Deterministic HTTP Responses:**
In `GlobalExceptionHandler`, catch `AliasConflictException` and `DataIntegrityViolationException` (for the race-condition case) and map both to `409 ALIAS_CONFLICT`. This ensures the HTTP contract is deterministic regardless of whether the conflict was caught at the application layer or the database layer.

---

### Stricter Rate Limiting for Custom Alias Endpoint

**Nginx Rate Limiting Layer (edge):**
Add a dedicated `limit_req_zone` for the custom alias path (i.e., `POST /api/urls` requests that include a `customAlias` field) at 2 requests/minute per IP. Since Nginx cannot inspect request body contents, apply the stricter zone to the entire `POST /api/urls` location block, or use a separate route such as `POST /api/urls/custom` if routing separation is preferred.

**Application Layer Rate Limiting with Custom Alias Detection:**
In the rate-limit interceptor, detect whether the incoming `POST /api/urls` request body contains a non-null `customAlias` field. If so, apply the stricter `custom-alias` bucket (2/min) instead of the default create bucket (5/min). Since reading the body in an interceptor is non-trivial, an alternative is to perform this check in the service layer and throw a `429` response via a custom exception if the Redis counter for that IP's custom-alias bucket is exceeded.

---

### Rollout Safety Notes

- No backfill migration is required for v2 custom aliases.
- Existing v1 short URLs continue using generated aliases.
- `custom_alias` remains optional and is written only for new requests where explicitly provided.
- Rollout plan: enable feature flag in dev, then staging, then production.
- Rollback plan: disable feature flag; existing custom aliases continue to resolve.

---

### Monitoring & Observability

**Metrics Dashboard (Grafana):**
Create a Grafana panel showing: custom alias creation rate over time, validation failure rate by error code, alias conflict rate, and rate-limit rejection count for the alias path. Use the existing `http.server.requests.total` metric (tagged by application, route, and outcome) filtered by route and outcome, plus any custom counters added for alias-specific events.

**Logging with Correlation IDs:**
Log a structured entry at INFO level on every alias creation attempt, including the `correlation_id`, the alias value (truncated if needed), the outcome (`created`, `conflict`, `invalid`, `feature_disabled`), and the client IP. This allows incident triage to trace a specific failed alias creation end-to-end through the log aggregation pipeline.

**Alerting Strategy:**

| Alert | Threshold | Action | Severity |
| --- | --- | --- | --- |
| Feature flag status | Any change | Page on-call (track rollout) | INFO |
| Validation failure rate | >10% sustained | Investigate validator rules | WARNING |
| Alias conflicts | >1/min | Immediate investigation (UNIQUE constraint OK?) | CRITICAL |
| Rate limit hits | >5/min | Monitor for abuse pattern | WARNING |
| Custom alias error rate | >1% | Disable feature flag and investigate | CRITICAL |

---

## Optional (Post-MVP)

- Add reserved-word management from configuration instead of hardcoded list.
- Add alias ownership/edit/transfer capabilities for authenticated users.
- Add alias policy controls per tenant or per account tier.
- Add conflict analytics dashboards segmented by client/user cohort.

## Updated Acceptance Criteria

- ✅ Alias creation works when feature flag is enabled
- ✅ Disabled state returns `400 FEATURE_UNAVAILABLE` error
- ✅ Invalid alias (format/reserved/length) returns `400 INVALID_ALIAS`
- ✅ Duplicate alias returns `409 ALIAS_CONFLICT` deterministically
- ✅ Stricter rate limiting (2/min) enforced with proper 429 responses
- ✅ No backfill migration required; existing generated aliases remain valid
- ✅ Feature flag rollback preserves existing custom alias resolution
- ✅ Monitoring metrics collected (feature flag checks, validation failures, conflicts, rate limits)
- ✅ Alerts configured for anomalies (conflicts, validation failures, rate limit abuse)
