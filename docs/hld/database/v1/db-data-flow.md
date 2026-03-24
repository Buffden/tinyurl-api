# Database Data Flow (v1)

> Read and write query paths for the v1 database baseline.

---

## 1) Write Path (Create)

1. App calls `nextval('url_mappings_id_seq')` to get the next ID.
2. App encodes the ID to Base62 to produce `short_code`.
3. App inserts the row:

   ```sql
   INSERT INTO url_mappings (short_code, original_url, created_at, expires_at)
   VALUES ($1, $2, NOW(), $3);
   ```

4. Repeated identical requests still create distinct rows with different `short_code` values. There is no deduplication in v1.

---

## 2) Read Path (Redirect)

1. App queries:

   ```sql
   SELECT original_url, expires_at
   FROM url_mappings
   WHERE short_code = $1;
   ```

   Uses the implicit `uq_short_code` index.

2. App checks `expires_at` in application logic.

3. **Found, not expired**: redirect (301 or 302 per ADR-003).
4. **Found, expired**: return `410 Gone`.
5. **Not found** (unknown code): return `404 Not Found`.

---

## 3) Soft Delete Path

Soft delete is not part of the v1 baseline. Administrative link removal, abuse blocking, and cache invalidation are introduced in v2.
