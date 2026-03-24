# UC-US-003: Expire a Short URL — v2 Active Cleanup

> v2 changes to [v1/UC-US-003](../v1/UC-US-003-expire-url.md). Read the v1 use case first.

---

## Metadata

| Field | Value |
|---|---|
| Use Case ID | UC-US-003 |
| Version | v2 |
| Priority | High |
| Status | Approved |
| Related FR | FR-003 |

---

## What Changes in v2

| Area | v1 Behaviour | v2 Behaviour |
|------|-------------|-------------|
| Expiry enforcement | Passive only (checked on redirect) | Passive + active (scheduled cleanup job) |
| Soft delete | Not used | Expired rows are soft-deleted after a 7-day grace period |
| Cache invalidation | No cache | Cleanup job invalidates Redis entries for affected short codes |
| Archival | None | Soft-deleted rows older than 90 days archived to cold storage (optional) |

---

## Active Cleanup — Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | System Timer | Triggers cleanup job at the scheduled interval (every 24 hours). |
| 2 | System | Queries for expired rows: `WHERE expires_at < now() - INTERVAL '7 days' AND is_deleted = false`. |
| 3 | System | Soft-deletes rows in batches: `UPDATE url_mappings SET is_deleted = true, deleted_at = now() WHERE id IN (...)`. |
| 4 | System | Invalidates Redis cache entries for affected short codes. |
| 5 | System | (Optional) Archives soft-deleted rows older than 90 days to cold storage. |

### Batching Strategy

- Process 1,000 rows per batch to avoid long-running transactions.
- Sleep between batches (100 ms) to reduce DB load.
- Run during off-peak hours if possible.

---

## Stale Cache Entry Handling

| Step | Actor | Action |
|---|---|---|
| 1 | System | Cached mapping has `expires_at` in the past but Redis TTL has not expired yet. |
| 2 | System | Redirect module checks `expires_at` from cached data, returns `410 Gone`. |
| 3 | System | Cache self-corrects when TTL expires (or when cleanup job invalidates the entry). |

> **Correctness guarantee**: Even without the cleanup job, expired URLs always return `410` because expiry is validated on the redirect path (v1 passive expiry remains intact).

---

## Error Flows

### Cleanup Job Fails

| Step | Actor | Action |
|---|---|---|
| 2a | System | DB query or update fails. |
| 2b | System | Logs error, retries on next scheduled interval. |
| 2c | System | No data loss: expired rows remain queryable but return `410` on access (passive expiry still works). |

---

## Postconditions

- Expired rows (7+ days past `expires_at`) are soft-deleted (`is_deleted = true`, `deleted_at` set).
- Redis cache entries for affected short codes are invalidated.
- Soft-deleted rows older than 90 days are archived (optional).
- Passive expiry (from v1) continues to work as a safety net for any rows the cleanup job has not yet processed.

---

## Design Decisions

- **7-day grace period before soft delete**: Allows for potential future "undo expiry" functionality. Prevents immediate deletion of recently expired links.
- **Batched processing**: Prevents long-running transactions that could lock the table.
- **Soft delete over hard delete**: Preserves audit trail. Short codes are never reissued — the `UNIQUE` constraint on `short_code` spans all rows including deleted ones.
