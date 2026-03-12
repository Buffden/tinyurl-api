# UC-US-003: Expire a Short URL (Passive)

---

## Metadata

| Field | Value |
|---|---|
| Use Case ID | UC-US-003 |
| Version | v1 |
| Priority | High |
| Status | Approved |
| Related FR | FR-003 |

---

## 1) Summary

Short URLs have a configurable expiry period. When a URL expires, the system stops serving redirects for it and returns a `410 Gone` response. In v1, expiry is enforced passively on the redirect path — no background cleanup job runs.

---

## 2) Actors

| Actor | Role |
|---|---|
| End User | Attempts to use an expired short URL. |
| System | Validates expiry on redirect request and returns `410 Gone`. |

---

## 3) Preconditions

1. The short URL was created with an `expires_at` timestamp.
2. The current time is past `expires_at`.

---

## 4) Triggers

- End user requests a redirect for an expired short code (`GET /<short_code>`).

---

## 5) Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `GET /<short_code>`. |
| 2 | System | Queries PostgreSQL for the URL mapping by `short_code`. |
| 3 | System | Checks `expires_at`. Value is in the past. |
| 4 | System | Returns `410 Gone` with error code `GONE`. |

No data modification occurs. The expired row remains in the database.

---

## 6) Error Flows

### 6a) Short Code Not Found

| Step | Actor | Action |
|---|---|---|
| 2a | System | No row found in DB. |
| 2b | System | Returns `404 Not Found` (handled by UC-US-002 error flow). |

---

## 7) Postconditions

- User receives a `410 Gone` response.
- No data modification — the expired row remains in the database.
- Expired rows accumulate in the DB. This is acceptable at v1 scale (10M rows ≈ 1.5–3 GB).

---

## 8) Design Decisions

- **No cleanup job in v1**: Expired rows are filtered at read time. This avoids background infrastructure in the baseline version. A periodic cleanup job is deferred to v2 (see [v2/UC-US-003](../v2/UC-US-003-expire-url.md)).
- **410 vs 404**: Expired links return `410 Gone` (resource existed and is intentionally unavailable) to distinguish from `404 Not Found` (never existed). See ADR-004.
