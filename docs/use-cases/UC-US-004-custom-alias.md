# UC-US-004: Create a Custom Alias (v2)

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

An end user creates a short URL with a custom, human-readable alias instead of a system-generated short code. Custom aliases are subject to stricter validation and rate limits.

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

- End user sends a `POST /api/urls` request with `{ "url": "<long_url>", "custom_alias": "<alias>" }`.

---

## 5) Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `POST /api/urls` with `url` and `custom_alias` fields. |
| 2 | System | Checks that the custom alias feature flag is enabled. |
| 3 | System | Validates URL format (same as UC-US-001). |
| 4 | System | Validates alias format: Base62 characters only, 4-32 characters in length. |
| 5 | System | Checks that the alias does not collide with reserved words (e.g., `create`, `health`, `api`). |
| 6 | System | Advisory pre-check: `SELECT 1 FROM url_mappings WHERE short_code = $1`. |
| 7 | System | Alias is available. Inserts row with the custom alias as `short_code`. The DB `UNIQUE` constraint on `short_code` is the true uniqueness enforcer; the pre-check is advisory only and does not eliminate the race window. |
| 8 | System | Warms Redis cache with the new mapping. |
| 9 | System | Returns `201 Created` with the custom short URL. |

---

## 6) Alternative Flows

### 6a) Feature Flag Disabled

| Step | Actor | Action |
|---|---|---|
| 2a | System | Custom alias feature flag is disabled. |
| 2b | System | Returns `400 Bad Request` with message indicating custom aliases are not available. |

### 6b) Alias Provided Without URL

| Step | Actor | Action |
|---|---|---|
| 3a | System | Request body contains `custom_alias` but no `url`. |
| 3b | System | Returns `400 Bad Request` with error code `INVALID_URL`. |

---

## 7) Error Flows

### 7a) Invalid Alias Format

| Step | Actor | Action |
|---|---|---|
| 4a | System | Alias contains non-Base62 characters or is outside the 4-32 character range. |
| 4b | System | Returns `400 Bad Request` with error code `INVALID_ALIAS`. |

### 7b) Reserved Word Collision

| Step | Actor | Action |
|---|---|---|
| 5a | System | Alias matches a reserved word (`create`, `health`, `api`, `admin`, `static`). |
| 5b | System | Returns `400 Bad Request` with error code `INVALID_ALIAS` and a message listing the restriction. |

### 7c) Alias Already Taken

| Step | Actor | Action |
|---|---|---|
| 6a | System | DB query returns a row — alias is already in use. |
| 6b | System | Returns `409 Conflict` with error code `ALIAS_CONFLICT`. |

### 7d) Rate Limit Exceeded

| Step | Actor | Action |
|---|---|---|
| 1a | System | Per-IP custom alias rate limit exceeded (2 req/min). |
| 1b | System | Returns `429 Too Many Requests` with `Retry-After` header. |

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

| Rule | Constraint | Error |
|---|---|---|
| Character set | `[0-9A-Za-z]` only | `INVALID_ALIAS` |
| Minimum length | 4 characters | `INVALID_ALIAS` |
| Maximum length | 32 characters | `INVALID_ALIAS` |
| Reserved words | Must not match reserved endpoint paths | `INVALID_ALIAS` |
| Uniqueness | Must not exist in `url_mappings` | `ALIAS_CONFLICT` |

---

## 10) Design Decisions

- **UNIQUE constraint is source of truth**: The DB `UNIQUE` constraint on `short_code` enforces uniqueness atomically. The pre-check SELECT at step 6 is advisory — it improves UX by returning a friendly `409 Conflict` before hitting the DB constraint, but two concurrent requests for the same alias can both pass the pre-check. The second insert will fail at the constraint, which the app catches and maps to `409 Conflict`. No additional application-level locking is required.
- **Feature-flagged**: Custom aliases are gated behind a configuration flag. This allows the feature to be disabled if it causes operational issues (e.g., namespace squatting).
- **Stricter rate limit**: Custom alias creation has a lower rate limit (2/min vs 5/min for standard) because alias validation requires a DB lookup.
- **Namespace reservation**: Once a custom alias is used, it cannot be reused even after expiry. This prevents confusion and ensures link permanence.
- **No re-assignment**: If a user creates `tinyurl.buffden.com/mylink`, that alias is permanently consumed. There is no mechanism to release it.
