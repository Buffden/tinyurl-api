# Database Data Flow (v2)

> Read and write query paths for TinyURL v2, including `url_hash` population and soft-delete handling.

---

## 1) Write Path (Create)

1. App computes `url_hash = SHA-256(original_url)`.
2. App calls `nextval('url_mappings_id_seq')` to get the next ID (served from local sequence cache if configured).
3. App encodes the ID to Base62 to produce `short_code`.
4. App inserts the row:

   ```sql
   INSERT INTO url_mappings (short_code, original_url, url_hash, created_at, expires_at, created_ip, user_agent)
   VALUES ($1, $2, $3, NOW(), $4, $5, $6);
   ```

5. Repeated identical requests still create distinct rows with different `short_code` values. `url_hash` is stored for lookup and analysis only; it does not participate in uniqueness enforcement.

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
