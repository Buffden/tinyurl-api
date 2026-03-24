# API Contract

> Low-level specification for all HTTP endpoints. This is the authoritative source for request/response schemas, error codes, and HTTP status codes. SpringDoc generates the Swagger UI from the code — this document governs what the code must produce.

---

## Base URL

| Environment | Base URL |
| --- | --- |
| Production | `https://go.buffden.com` |
| Local (Docker Compose) | `http://localhost` |

All API endpoints are prefixed with `/api`. The redirect endpoint (`GET /{short_code}`) is at the root — not under `/api` — because it is user-facing and must be short.

---

## Versioning

No URL versioning in v1 (`/v1/api/urls` is not used). If a breaking API change is needed, a new version prefix will be introduced under a separate ADR. All current clients are internal (the Angular SPA), so versioning is deferred.

---

## Standard Error Response

All error responses use this shape, regardless of endpoint or status code:

```json
{
  "code": "INVALID_URL",
  "message": "URL must be a valid HTTP or HTTPS address (max 2048 characters)."
}
```

| Field | Type | Description |
| --- | --- | --- |
| `code` | `string` | Machine-readable error code. Uppercase snake_case. Never changes between versions. |
| `message` | `string` | Human-readable description. For display in the UI or logs. May change. |

---

## Error Code Registry

| Code | HTTP Status | When |
| --- | --- | --- |
| `INVALID_URL` | 400 | URL is malformed, uses a non-HTTP/HTTPS scheme, or exceeds 2048 characters |
| `INVALID_EXPIRY` | 400 | `expires_in_days` is provided but is not a positive integer, or exceeds the maximum (3650 days / 10 years) |
| `RATE_LIMIT_EXCEEDED` | 429 | Per-IP rate limit exceeded on the create endpoint |
| `NOT_FOUND` | 404 | Short code does not exist in the system |
| `GONE` | 410 | Short code existed but has expired or been soft-deleted |
| `SERVICE_UNAVAILABLE` | 503 | Database is unreachable; request cannot be served |
| `INTERNAL_ERROR` | 500 | Unexpected server error |
| `ALIAS_TAKEN` | 409 | Requested custom alias already exists *(v2)* |
| `INVALID_ALIAS` | 400 | Custom alias fails format validation (not Base62, wrong length, reserved word) *(v2)* |
| `ALIAS_FEATURE_DISABLED` | 400 | Custom alias feature flag is off *(v2)* |
| `UNAUTHORIZED` | 401 | Admin endpoint called without valid credentials *(v2)* |

---

## Endpoints

---

### 1. Create Short URL

**`POST /api/urls`**

Creates a new short URL mapping. Non-idempotent — identical requests produce distinct short codes.

#### Request

**Headers**

| Header | Required | Value |
| --- | --- | --- |
| `Content-Type` | Yes | `application/json` |

**Body**

```json
{
  "url": "https://www.example.com/some/very/long/path?query=value",
  "expiresInDays": 30
}
```

| Field | Type | Required | Constraints | Default |
| --- | --- | --- | --- | --- |
| `url` | `string` | Yes | Valid HTTP/HTTPS URL, max 2048 characters | — |
| `expiresInDays` | `integer` | No | Positive integer, max 3650 (10 years) | 180 |
| `alias` | `string` | No | Base62, 4–32 chars, not a reserved word *(v2 only, feature-flagged)* | — |

#### Responses

**`201 Created`** — Short URL successfully created.

```json
{
  "shortUrl": "https://go.buffden.com/aB3xYz",
  "shortCode": "aB3xYz",
  "originalUrl": "https://www.example.com/some/very/long/path?query=value",
  "expiresAt": "2026-09-10T12:00:00Z"
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `shortUrl` | `string` | Fully qualified short URL ready to share |
| `shortCode` | `string` | The code segment only (useful for constructing URLs client-side) |
| `originalUrl` | `string` | The original URL as submitted |
| `expiresAt` | `string (ISO 8601)` | UTC timestamp. `null` only if expiry was explicitly set to never — not applicable in v1 since all URLs expire |

**`400 Bad Request`** — Validation failure.

```json
{
  "code": "INVALID_URL",
  "message": "URL must be a valid HTTP or HTTPS address (max 2048 characters)."
}
```

Possible codes: `INVALID_URL`, `INVALID_EXPIRY`, `INVALID_ALIAS` (v2), `ALIAS_FEATURE_DISABLED` (v2).

**`409 Conflict`** *(v2 only)* — Custom alias already taken.

```json
{
  "code": "ALIAS_TAKEN",
  "message": "The alias 'my-link' is already in use."
}
```

**`429 Too Many Requests`** — Rate limit exceeded.

```json
{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "Too many requests. Please try again later."
}
```

**Response Headers on 429**

| Header | Value |
| --- | --- |
| `Retry-After` | Seconds until the rate limit window resets |

**`500 Internal Server Error`**

```json
{
  "code": "INTERNAL_ERROR",
  "message": "An unexpected error occurred. Please try again."
}
```

---

### 2. Redirect

**`GET /{short_code}`**

Resolves a short code and redirects to the original URL. This is a pure read — no state is modified.

#### Request

No body. No special headers required.

**Path parameter**

| Parameter | Type | Constraints |
| --- | --- | --- |
| `short_code` | `string` | Base62, 4–8 characters |

#### Responses

**`301 Moved Permanently`** — Short URL has no explicit expiry (permanent link).

```
Location: https://www.example.com/some/very/long/path?query=value
```

No body. Browser will cache this redirect — subsequent requests will not hit the server.

**`302 Found`** — Short URL has an explicit expiry (temporary link).

```
Location: https://www.example.com/some/very/long/path?query=value
```

No body. Browser will not cache this redirect.

**`404 Not Found`** — Short code does not exist.

```json
{
  "code": "NOT_FOUND",
  "message": "No URL found for this short code."
}
```

**`410 Gone`** — Short code existed but has expired or been removed.

```json
{
  "code": "GONE",
  "message": "This short URL has expired or been removed."
}
```

Both expired links (`expires_at` in the past) and soft-deleted links (`is_deleted = true`) return `410` with the same `GONE` code. The distinction is internal — the client does not need to know which case it is.

**`503 Service Unavailable`** — Database is unreachable.

```json
{
  "code": "SERVICE_UNAVAILABLE",
  "message": "The service is temporarily unavailable. Please try again."
}
```

**Response Headers on 503**

| Header | Value |
| --- | --- |
| `Retry-After` | `30` (fixed — retry after 30 seconds) |

---

### 3. Health Check

**`GET /actuator/health`**

Returns the overall health status of the application. Provided by Spring Boot Actuator. Used by Docker/container health checks and backend runtime probes behind CloudFront + Nginx.

#### Response

**`200 OK`** — Application is healthy.

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```

**`503 Service Unavailable`** — One or more components are down.

```json
{
  "status": "DOWN",
  "components": {
    "db": {
      "status": "DOWN"
    }
  }
}
```

> `GET /actuator/health/liveness` and `GET /actuator/health/readiness` are also available for Kubernetes probes (v2 deployment).

---

### 4. Admin Soft Delete *(v2 only)*

**`DELETE /api/urls/{short_code}`**

Marks a URL mapping as soft-deleted. The record is not removed from the database. Subsequent redirects to this code return `410 Gone`. Admin-only — requires authentication.

#### Request

**Path parameter**

| Parameter | Type | Constraints |
| --- | --- | --- |
| `short_code` | `string` | Base62, 4–8 characters |

**Authentication**: mechanism to be defined in a v2 security ADR. Placeholder: `Authorization: Bearer <token>` header.

#### Responses

**`204 No Content`** — Successfully soft-deleted. No body.

**`401 Unauthorized`** — Missing or invalid credentials.

```json
{
  "code": "UNAUTHORIZED",
  "message": "Valid admin credentials are required."
}
```

**`404 Not Found`** — Short code does not exist.

```json
{
  "code": "NOT_FOUND",
  "message": "No URL found for this short code."
}
```

**`410 Gone`** — Short code was already soft-deleted (idempotent — can be changed to `204` if preferred).

```json
{
  "code": "GONE",
  "message": "This short URL has already been removed."
}
```

---

## Reserved Short Codes

The following aliases must never be issued by the ID generation sequence and must be rejected if submitted as custom aliases in v2:

| Reserved word | Reason |
| --- | --- |
| `api` | API prefix |
| `actuator` | Spring Boot Actuator namespace |
| `health` | Health check endpoint |
| `admin` | Future admin panel |
| `static` | Static file serving |
| `assets` | Static assets |
| `favicon.ico` | Browser default request |
| `robots.txt` | Crawler directive |
| `sitemap.xml` | SEO sitemap |

> The Base62-encoded sequence starts at ID 1, producing short codes like `0000001` padded to 7 chars — or unpadded like `1`, `2`, `Z`. None of these collide with the reserved words above. Custom alias validation (v2) must check against this list explicitly.

---

## Notes

**301 vs 302 decision**: Documented in [ADR-003](../architecture/00-baseline/adr/ADR-003-redirect-status-code.md). Short summary: 301 for permanent (no `expires_at`), 302 for any link with an explicit expiry.

**No `X-Request-ID` in v1**: Correlation IDs are generated server-side and written to structured logs. They are not returned in response headers in v1. If the frontend needs them for debugging, add `X-Request-ID` as a response header in v2.

**No pagination**: `POST /api/urls` returns a single resource. There is no list endpoint — listing all URLs is out of scope for all versions.
