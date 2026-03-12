# UC-US-004: Create a Custom Alias

---

## Metadata

| Field | Value |
|---|---|
| Use Case ID | UC-US-004 |
| Version | v2 |
| Priority | Medium |
| Status | Approved (feature-flagged) |
| Related FR | FR-006 |

---

## 1) Summary

An end user creates a short URL with a custom, human-readable alias instead of a system-generated short code. Custom aliases are subject to stricter validation and rate limits. This use case exists only in v2 — there is no v1 equivalent.

---

## 2) Actors

| Actor | Role |
|---|---|
| End User | Provides a long URL and desired custom alias. |
| System | Validates alias, checks uniqueness, and creates the mapping. |

---

## 3) Preconditions

1. Custom alias feature flag is enabled.
2. The user has not exceeded the custom alias rate limit (2 req/min).
3. The system is operational.

---

## 4) Triggers

- End user sends a `POST /api/urls` request with `{ "url": "<long_url>", "alias": "<alias>" }`.

---

## 5) Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `POST /api/urls` with `url` and `alias` fields. |
| 2 | System | Checks that the custom alias feature flag is enabled. |
| 3 | System | Checks per-IP rate limit (2 req/min for custom alias). |
| 4 | System | Validates URL format (same as UC-US-001). |
| 5 | System | Validates alias format: Base62 characters only, 4–32 characters in length. |
| 6 | System | Checks that the alias does not collide with reserved words (`api`, `health`, `actuator`, `admin`, `static`, `create`). |
| 7 | System | Advisory pre-check: `SELECT 1 FROM url_mappings WHERE short_code = $1`. |
| 8 | System | Alias is available. Inserts row with the custom alias as `short_code`. The DB `UNIQUE` constraint is the true uniqueness enforcer; the pre-check is advisory only. |
| 9 | System | Warms Redis cache with the new mapping. |
| 10 | System | Returns `201 Created` with the custom short URL. |

---

## 6) Alternative Flows

### 6a) Feature Flag Disabled

| Step | Actor | Action |
|---|---|---|
| 2a | System | Custom alias feature flag is disabled. |
| 2b | System | Returns `400 Bad Request` with error code `ALIAS_FEATURE_DISABLED`. |

### 6b) Alias Provided Without URL

| Step | Actor | Action |
|---|---|---|
| 4a | System | Request body contains `alias` but no `url`. |
| 4b | System | Returns `400 Bad Request` with error code `INVALID_URL`. |

---

## 7) Error Flows

### 7a) Invalid Alias Format

| Step | Actor | Action |
|---|---|---|
| 5a | System | Alias contains non-Base62 characters or is outside the 4–32 character range. |
| 5b | System | Returns `400 Bad Request` with error code `INVALID_ALIAS`. |

### 7b) Reserved Word Collision

| Step | Actor | Action |
|---|---|---|
| 6a | System | Alias matches a reserved word. |
| 6b | System | Returns `400 Bad Request` with error code `INVALID_ALIAS`. |

### 7c) Alias Already Taken

| Step | Actor | Action |
|---|---|---|
| 7a | System | Advisory pre-check returns a row — alias is already in use. |
| 7b | System | Returns `409 Conflict` with error code `ALIAS_TAKEN`. |

### 7d) Race Condition (concurrent alias claim)

| Step | Actor | Action |
|---|---|---|
| 8a | System | Two concurrent requests pass the advisory pre-check. First insert succeeds. Second insert violates `UNIQUE` constraint. |
| 8b | System | Catches the constraint violation, returns `409 Conflict` with error code `ALIAS_TAKEN`. |

### 7e) Rate Limit Exceeded

| Step | Actor | Action |
|---|---|---|
| 3a | System | Per-IP custom alias rate limit exceeded (2 req/min). |
| 3b | System | Returns `429 Too Many Requests` with `Retry-After` header and error code `RATE_LIMIT_EXCEEDED`. |

---

## 8) Postconditions

### Success

- A new row exists in `url_mappings` with `short_code` set to the custom alias.
- The alias is globally unique and cannot be reused even after expiry (namespace reservation).
- Redis cache is warmed with the new mapping.

### Failure

- No row is inserted. The alias remains available for future use.

---

## 9) Validation Rules

| Rule | Constraint | Error Code |
|---|---|---|
| Character set | `[0-9A-Za-z]` only | `INVALID_ALIAS` |
| Minimum length | 4 characters | `INVALID_ALIAS` |
| Maximum length | 32 characters | `INVALID_ALIAS` |
| Reserved words | Must not match reserved endpoint paths | `INVALID_ALIAS` |
| Uniqueness | Must not exist in `url_mappings` (including deleted rows) | `ALIAS_TAKEN` |

---

## 10) Design Decisions

- **UNIQUE constraint is source of truth**: The DB `UNIQUE` constraint on `short_code` enforces uniqueness atomically. The pre-check SELECT is advisory — it improves UX by returning a friendly `409` before hitting the constraint, but does not eliminate the race window.
- **Feature-flagged**: Custom aliases are gated behind a configuration flag (`tinyurl.features.custom-alias=true`). Disabled by default.
- **Stricter rate limit**: 2 req/min (vs 5 req/min for standard creates) because alias validation requires a DB lookup.
- **Namespace reservation**: Once a custom alias is used, it cannot be reused even after expiry or deletion. The `UNIQUE` constraint spans all rows including soft-deleted ones.
