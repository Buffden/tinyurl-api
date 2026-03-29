# Phase B — Secrets, Config & Code Changes

**Goal:** Application can boot in production with correct config. All code changes made before first deploy.
**Prerequisites:** Phase A complete (RDS endpoint available, AWS infrastructure exists)
**Estimated time:** 1–2 hours

---

## Checklist

- [ ] Step 1 — Populate SSM Parameter Store
- [ ] Step 2 — Add CORS to Spring Boot
- [ ] Step 3 — Update `environment.prod.ts`
- [ ] Step 4 — Create `docker-compose.prod.yml`
- [ ] Step 5 — Create `nginx.prod.conf`
- [ ] Step 6 — Verify SSM dependency in `build.gradle.kts`
- [ ] Step 7 — Verify `application-prod.yaml` SSM import

---

## Step 1 — Populate SSM Parameter Store

Go to **AWS Systems Manager → Parameter Store → Create parameter** for each:

| Name | Type | Value |
|---|---|---|
| `/tinyurl/prod/spring/datasource/username` | String | `tinyurl_appuser` |
| `/tinyurl/prod/spring/datasource/password` | SecureString | `<tinyurl_appuser password>` |
| `/tinyurl/prod/spring/flyway/user` | String | `tinyurl` |
| `/tinyurl/prod/spring/flyway/password` | SecureString | `<master user password from Phase A Step 6>` |
| `/tinyurl/prod/tinyurl/base-url` | String | `https://go.buffden.com` |

> `SecureString` encrypts the password using KMS — it will not appear in plaintext in the console.
>
> **Why separate datasource and flyway credentials?** Spring Boot uses `spring.flyway.user` for Flyway migrations (DDL) and `spring.datasource.username` for all runtime queries. `tinyurl_appuser` has DML-only access; the master user `tinyurl` retains DDL access for migrations. See `docs/security/DB_LEAST_PRIVILEGE.md` for the full setup.
>
> **Why these path names?** Spring Cloud AWS strips the `/tinyurl/prod/` prefix and converts `/` to `.` in the remaining path. So `/tinyurl/prod/spring/datasource/username` maps to `spring.datasource.username`. Wrong path names mean the app silently uses defaults and fails to connect to RDS.
>
> **No SSM param for the DB URL** — the URL is constructed dynamically at deploy time using `RDS_ENDPOINT` and passed as a docker-compose env var (see Step 4).

---

## Step 2 — Add CORS to Spring Boot

The Angular SPA at `https://tinyurl.buffden.com` calls the API at `https://go.buffden.com`. These are different origins, so Spring Boot must explicitly allow cross-origin requests.

Create the file `tinyurl/src/main/java/com/tinyurl/config/CorsConfig.java`:

```java
package com.tinyurl.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${tinyurl.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        return new CorsFilter(source);
    }
}
```

Add the CORS config property to `application.yaml` (default/dev):

```yaml
tinyurl:
  cors:
    allowed-origins:
      - "http://localhost:4200"
```

Add to `application-prod.yaml`:

```yaml
tinyurl:
  cors:
    allowed-origins:
      - "https://tinyurl.buffden.com"
```

> This keeps CORS locked to `localhost:4200` in dev and `tinyurl.buffden.com` in prod. No wildcard origins.

---

## Step 3 — Update `environment.prod.ts`

File: `tinyurl-gui/src/environments/environment.prod.ts`

Change from:
```typescript
export const environment = {
  production: true,
  apiUrl: 'https://tinyurl.buffden.com/api'
};
```

To:
```typescript
export const environment = {
  production: true,
  apiUrl: 'https://go.buffden.com/api'
};
```

> This is the URL the Angular form POSTs to when creating a short link.

---

## Step 4 — Create `docker-compose.prod.yml`

Create this file at the root of the backend repo (same level as `docker-compose.yml`):

```yaml
# docker-compose.prod.yml
# Production only — no Postgres (that is RDS)
# Deployed on EC2, managed via SSM RunCommand

services:
  nginx:
    image: nginx:1.27-alpine
    container_name: tinyurl-nginx
    ports:
      - "80:80"
    volumes:
      - ./infra/nginx/nginx.prod.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      app:
        condition: service_healthy
    restart: unless-stopped
    logging:
      driver: awslogs
      options:
        awslogs-group: /tinyurl/prod
        awslogs-region: us-east-1
        awslogs-stream: nginx

  app:
    image: ghcr.io/buffden/tinyurl-api:${IMAGE_TAG}
    container_name: tinyurl-app
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://${RDS_ENDPOINT}:5432/tinyurl_production_db
    expose:
      - "8080"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 60s
    restart: unless-stopped
    logging:
      driver: awslogs
      options:
        awslogs-group: /tinyurl/prod
        awslogs-region: us-east-1
        awslogs-stream: app
```

> `IMAGE_TAG` and `RDS_ENDPOINT` are environment variables set on the EC2 instance before running this file.
> The `awslogs` driver ships container logs directly to CloudWatch — no log agent needed.

---

## Step 5 — Create `nginx.prod.conf`

Create this file at `infra/nginx/nginx.prod.conf`:

```nginx
events {
    worker_connections 1024;
}

http {
    # Rate limiting: 40 requests per minute per IP on POST /api/urls
    limit_req_zone $binary_remote_addr zone=create_url:10m rate=40r/m;

    server {
        listen 80;
        server_name go.buffden.com;

        # Security headers
        add_header X-Frame-Options "DENY" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;

        # Allow ALB health checks — MUST be before the blanket /actuator/ block
        location = /actuator/health {
            proxy_pass http://app:8080;
            proxy_set_header Host $host;
        }

        # Block all other actuator endpoints from outside
        location /actuator/ {
            return 403;
        }

        # Rate limit URL creation
        location = /api/urls {
            limit_req zone=create_url burst=10 nodelay;
            proxy_pass http://app:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        # All other requests (redirects, health via internal path)
        location / {
            proxy_pass http://app:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
    }
}
```

> `HSTS` (`Strict-Transport-Security`) header is intentionally omitted here — the ALB handles HTTPS enforcement. Adding HSTS at the Nginx level (HTTP only internally) would have no effect and could cause confusion.

---

## Step 6 — Verify SSM Dependency in `build.gradle.kts`

Open `tinyurl/build.gradle.kts` and confirm this dependency exists:

```kotlin
implementation("io.awspring.cloud:spring-cloud-aws-starter-parameter-store")
```

If it is missing, add it. Also confirm the Spring Cloud AWS BOM version is declared:

```kotlin
implementation(platform("io.awspring.cloud:spring-cloud-aws-dependencies:3.1.1"))
```

---

## Step 7 — Verify `application-prod.yaml` SSM Import

Open `tinyurl/src/main/resources/application-prod.yaml` and confirm:

```yaml
spring:
  config:
    import: "aws-parameterstore:/tinyurl/prod/"
```

This tells Spring Boot to load all parameters under `/tinyurl/prod/` from SSM at startup. The parameter names map to properties:

| SSM path | Spring property |
|---|---|
| `/tinyurl/prod/spring/datasource/username` | `spring.datasource.username` |
| `/tinyurl/prod/spring/datasource/password` | `spring.datasource.password` |
| `/tinyurl/prod/spring/flyway/user` | `spring.flyway.user` |
| `/tinyurl/prod/spring/flyway/password` | `spring.flyway.password` |
| `/tinyurl/prod/tinyurl/base-url` | `tinyurl.base-url` |

> The datasource URL is NOT loaded from SSM — it is constructed at deploy time from `RDS_ENDPOINT` and passed as `SPRING_DATASOURCE_URL` env var via docker-compose.
> If the SSM paths or Spring property names don't match, the app will silently use defaults and fail to connect to RDS.

---

## CloudWatch Log Group Setup

Before Phase C, create the log group that Docker will write to:

1. Go to **CloudWatch → Log groups → Create log group**
2. Name: `/tinyurl/prod`
3. Retention: 30 days
4. Click **Create**

The `awslogs` driver in `docker-compose.prod.yml` will write to this group automatically.

---

## Verification Before Phase C

Run these checks locally before deploying:

```bash
# Build the production image — must succeed with no errors
./gradlew bootJar
docker build -t ghcr.io/buffden/tinyurl-api:test .

# Build Angular for production — must succeed under budget
cd tinyurl-gui
ng build --configuration=production
# Check: no "Budget exceeded" errors in output

# Verify environment.prod.ts has the correct URL
grep apiUrl src/environments/environment.prod.ts
# Expected: https://go.buffden.com/api
```

---

## How it all connects at runtime

```text
Browser
  │
  ▼
Route 53 (go.buffden.com)
  │
  ▼
ALB (HTTPS:443 → HTTP:80 to EC2)
  │
  ▼
Nginx container (port 80)
  ├── rate limits POST /api/urls
  ├── blocks /actuator/
  └── proxies everything else
        │
        ▼
      Spring Boot container (port 8080, internal only)
        ├── reads DB credentials from SSM (via application-prod.yaml import)
        ├── reads DB URL from SPRING_DATASOURCE_URL env var (via docker-compose)
        └── connects to RDS PostgreSQL
```

**Proceed to [Phase C](PHASE_C_FIRST_MANUAL_DEPLOY.md).**
