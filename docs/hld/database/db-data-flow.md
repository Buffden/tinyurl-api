# Database Data Flow

> Read and write query paths for TinyURL, including deduplication and soft-delete handling.

---

## 1) Write Path (Create)

1. App computes `url_hash = SHA-256(original_url)`.
2. App queries for an existing mapping:
   ```sql
   SELECT short_code FROM url_mappings
   WHERE url_hash = $1 AND is_deleted = FALSE;
   ```
   - **If found**: return the existing short URL (deduplication hit — no insert needed).
   - **If not found**: proceed to insert.
3. App calls `nextval('url_mappings_id_seq')` to get the next ID (served from local sequence cache if configured).
4. App encodes the ID to Base62 to produce `short_code`.
5. App inserts the row:
   ```sql
   INSERT INTO url_mappings (short_code, original_url, url_hash, created_at, expires_at, created_ip, user_agent)
   VALUES ($1, $2, $3, NOW(), $4, $5, $6);
   ```
   `uq_url_hash` handles the race condition if two identical URLs are submitted concurrently — the second insert will fail the unique constraint and the app retries the deduplication lookup.

---

## 2) Read Path (Redirect)

1. App queries:

   ```sql
   SELECT original_url, expires_at
   FROM url_mappings
   WHERE short_code = $1
     AND is_deleted = FALSE;
   ```

   Uses `idx_short_code_active` partial index — only active rows are scanned.

2. App checks `expires_at` in application logic.

3. **Found, not expired**: redirect (301 or 302 per ADR-003).
4. **Found, expired**: return `410 Gone`.
5. **Not found** (unknown code): return `404 Not Found`.
6. **Soft-deleted distinction** (optional — if 404 vs 410 precision is needed for deleted codes):
   Issue a secondary query on miss:
   ```sql
   SELECT 1 FROM url_mappings
   WHERE short_code = $1 AND is_deleted = TRUE;
   ```
   If found → return `410 Gone`. This is rare (soft deletes are infrequent) and adds negligible overhead.

---

## 3) Soft Delete Path (Admin / Internal)

```sql
UPDATE url_mappings
SET is_deleted = TRUE,
    deleted_at = NOW()
WHERE short_code = $1
  AND is_deleted = FALSE;
```

- `chk_deleted_consistency` constraint ensures `deleted_at` is always set when `is_deleted = TRUE`.
- The `short_code` namespace is permanently reserved after deletion — it is never reissued.
- Cache must be invalidated for this `short_code` in Redis after a soft delete to prevent stale redirects within the TTL window.
