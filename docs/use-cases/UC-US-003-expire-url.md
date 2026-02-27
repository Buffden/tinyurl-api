# UC-US-003: Expire a Short URL

---

## Metadata

| Field | Value |
|---|---|
| Use Case ID | UC-US-003 |
| Version | v1 (cleanup in v2) |
| Priority | High |
| Status | Approved |
| Related FR | FR-003 |

---

## 1) Summary

Short URLs have a configurable expiry period. When a URL expires, the system stops serving redirects for it and returns a `410 Gone` response. In v2, a periodic cleanup job archives expired rows.

---

## 2) Actors

| Actor | Role |
|---|---|
| End User | Attempts to use an expired short URL. |
| System Timer | (v2) Triggers periodic cleanup of expired rows. |
| System | Validates expiry on redirect requests; runs cleanup jobs. |

---

## 3) Preconditions

1. The short URL was created with an `expires_at` timestamp.
2. The current time is past `expires_at`.

---

## 4) Triggers

- **Passive (v1/v2)**: End user requests a redirect for an expired short code.
- **Active (v2)**: Scheduled cleanup job runs on a periodic interval.

---

## 5) Main Flow — Passive Expiry (v1)

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `GET /<short_code>`. |
| 2 | System | Looks up the short code in DB. |
| 3 | System | Checks `expires_at`. Value is in the past. |
| 4 | System | Returns `410 Gone` with a message indicating the link has expired. |

No data modification occurs. The row remains in the database.

---

## 6) Main Flow — Active Cleanup (v2)

| Step | Actor | Action |
|---|---|---|
| 1 | System Timer | Triggers cleanup job at the scheduled interval (e.g., every 24 hours). |
| 2 | System | Queries for expired rows: `WHERE expires_at < now() - INTERVAL '7 days' AND is_deleted = false`. |
| 3 | System | Soft-deletes rows in batches: `UPDATE url_mappings SET is_deleted = true WHERE id IN (...)`. |
| 4 | System | (Optional) Archives soft-deleted rows older than 90 days to cold storage. |
| 5 | System | Invalidates Redis cache entries for affected short codes. |

### Batching Strategy

- Process 1,000 rows per batch to avoid long-running transactions.
- Sleep between batches (e.g., 100 ms) to reduce DB load.
- Run during off-peak hours if possible.

---

## 7) Error Flows

### 7a) Cleanup Job Fails

| Step | Actor | Action |
|---|---|---|
| 2a | System | DB query or update fails. |
| 2b | System | Logs error, retries on next scheduled interval. |
| 2c | System | No data loss: expired rows remain queryable but return 410 on access. |

### 7b) Stale Cache Entry (v2)

| Step | Actor | Action |
|---|---|---|
| 1a | System | Cached mapping has `expires_at` in the past but TTL has not expired yet. |
| 1b | System | Redirect module checks `expires_at` from cached data, returns 410. |
| 1c | System | Cache self-corrects when TTL expires. |

---

## 8) Postconditions

### Passive Expiry
- User receives a `410 Gone` response.
- No data modification; row remains in DB.

### Active Cleanup (v2)
- Expired rows are soft-deleted.
- Cache entries are invalidated.
- Soft-deleted rows older than retention period are archived.

---

## 9) Design Decisions

- **v1 does not run cleanup**: Expired rows are filtered at read time. This avoids background infrastructure in the baseline version.
- **7-day grace period before soft delete**: Allows for potential future "undo expiry" functionality.
- **Batched processing**: Prevents long-running transactions that could lock the table.

---

## 10) Sequence Diagram

See [expired-url-flow.puml](../diagrams/sequence/expired-url-flow.puml).
