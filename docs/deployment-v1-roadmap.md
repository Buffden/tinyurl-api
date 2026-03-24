# V1 Deployment Roadmap — `go.buffden.com`

## Current State

| Component | Status |
|-----------|--------|
| Backend (Spring Boot + PostgreSQL) | Ready — dockerized |
| Frontend (Angular 19) | Ready — build output will be deployed to S3 |
| nginx (prod) | Done — SSL, rate limiting, API + short-code routing |
| docker-compose | Partial — needs base/override split, frontend removed from prod |

**Gaps to close before deploying:**

1. docker-compose needs restructuring into base + dev override + prod override
2. `TINYURL_BASE_URL` hardcoded to `localhost`
3. No production secrets / env config
4. S3 bucket and CloudFront distribution not yet created
5. Spring Boot needs CORS configured (SPA on different subdomain)

---

## Architecture

Two separate concerns, two separate hosting targets (per ADR-006):

```plaintext
                  ┌─ SPA (static) ─────────────────────────────────┐
Browser           │                                                  │
  │               │  tinyurl.buffden.com                        │
  │               │    └── CloudFront ──► S3 bucket                 │
  │               │        (Angular SPA, SSG/CSR, index.html 404)  │
  │               └──────────────────────────────────────────────────┘
  │
  │               ┌─ Backend (VPS) ─────────────────────────────────┐
  │               │                                                  │
  └──────────────►│  go.buffden.com:443                        │
                  │    └── nginx (SSL, rate limiting)               │
                  │          ├── /api/*         ──► backend:8080    │
                  │          ├── /actuator/*    ──► backend:8080    │
                  │          └── /{shortCode}   ──► backend:8080    │
                  │               (301/302 redirect)                │
                  │                                                  │
                  │  backend ──► postgres:5432  (internal only)     │
                  └──────────────────────────────────────────────────┘
```

**Why two subdomains?**
The HLD places two separate CloudFront distributions:
one in front of the VPS (API + redirects) and one in front of S3 (SPA).
For V1 on a VPS without full AWS infrastructure, the API CloudFront layer is
deferred — nginx on the VPS handles SSL termination directly. The SPA follows
ADR-006 fully: S3 + CloudFront under `tinyurl.buffden.com`.

Short URLs shared with users are `https://go.buffden.com/abc123` (clean).
The app users visit is `https://tinyurl.buffden.com`.

---

## Environment Strategy

Two environments run independently — local dev while production serves live traffic.
The pattern is **Docker Compose override files**: base config shared, environment-specific
differences layered on top.

```plaintext
docker-compose.yml           # base — postgres + backend skeleton (both envs)
docker-compose.override.yml  # dev  — auto-loaded by `docker compose up`
docker-compose.prod.yml      # prod — explicitly merged on the server

.env.dev                     # dev vars (safe to commit, no real secrets)
.env.prod                    # prod vars (gitignored, never committed)

infra/nginx/nginx.conf       # dev  — plain HTTP, port 8080
infra/nginx/nginx.prod.conf  # prod — HTTPS, SSL, rate limiting (done)

tinyurl-gui/nginx.dev.conf   # dev container — SPA + /api/ proxy
tinyurl-gui/nginx.prod.conf  # prod container — static files only
                             # (used when running prod build locally in Docker;
                             #  NOT used in actual production — S3 handles serving)
```

### Day-to-day usage

```bash
# Local dev — base + override auto-merged
docker compose up --build

# Production server — explicitly merge prod overrides
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  --env-file .env.prod up -d --build
```

---

## Phase 1: Code Changes (local, before deploying)

### 1A. Restructure `docker-compose.yml` into base + override files

**`docker-compose.yml`** — base, shared by both environments.
`SPRING_DATASOURCE_URL` is intentionally absent — each environment sets it:
dev hardcodes it in the override; prod reads it from `.env.prod` pointing to RDS.

```yaml
services:
  backend:
    build:
      context: ./tinyurl
      dockerfile: Dockerfile
    container_name: tinyurl-backend
    environment:
      SPRING_DATASOURCE_USERNAME: ${SPRING_DATASOURCE_USERNAME}
      SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}
      SERVER_PORT:                8080
    expose:
      - "8080"
    networks:
      - app_internal
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 40s
    restart: unless-stopped

networks:
  public:
  app_internal:
    internal: true
```

---

**`docker-compose.override.yml`** — dev, auto-loaded.
Owns the postgres container — prod uses RDS instead.

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: tinyurl-postgres
    environment:
      POSTGRES_DB:       ${POSTGRES_DB}
      POSTGRES_USER:     ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      PGDATA:            /var/lib/postgresql/data/pgdata
    command:
      - postgres
      - -c
      - max_connections=200
      - -c
      - shared_buffers=256MB
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./infra/postgres/init:/docker-entrypoint-initdb.d:ro
    networks:
      - app_internal
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s
    restart: unless-stopped

  nginx:
    image: nginx:1.27-alpine
    container_name: tinyurl-nginx
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "8080:80"
    networks:
      - public
      - app_internal
    volumes:
      - ./infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
    restart: unless-stopped

  frontend:
    build:
      context: ./tinyurl-gui
      dockerfile: Dockerfile
      target: dev
    container_name: tinyurl-frontend
    ports:
      - "4200:4200"
    networks:
      - public
    volumes:
      - ./tinyurl-gui/src:/app/src
    restart: unless-stopped

  backend:
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/tinyurl
      TINYURL_BASE_URL:      http://localhost:8080

volumes:
  postgres-data:
```

---

**`docker-compose.prod.yml`** — prod, explicitly specified.
No `postgres` service — backend connects to RDS via `SPRING_DATASOURCE_URL` in `.env.prod`.
No `frontend` service — Angular SPA is deployed to S3 + CloudFront (ADR-006).

```yaml
services:
  nginx:
    image: nginx:1.27-alpine
    container_name: tinyurl-nginx
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "80:80"
      - "443:443"
    networks:
      - public
      - app_internal
    volumes:
      - ./infra/nginx/nginx.prod.conf:/etc/nginx/nginx.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
    restart: unless-stopped

  backend:
    environment:
      SPRING_DATASOURCE_URL:   ${SPRING_DATASOURCE_URL}
      TINYURL_BASE_URL:        https://go.buffden.com
      SPRING_PROFILES_ACTIVE:  prod
      TINYURL_ALLOWED_ORIGINS: https://tinyurl.buffden.com
```

---

### 1B. Create env files

**`.env.dev`** — safe to commit (no real secrets, local-only values):

```bash
POSTGRES_DB=tinyurl
POSTGRES_USER=tinyurl
POSTGRES_PASSWORD=tinyurl
SPRING_DATASOURCE_USERNAME=tinyurl
SPRING_DATASOURCE_PASSWORD=tinyurl
```

**`.env.prod`** — never commit, add to `.gitignore`:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://<rds-endpoint>.rds.amazonaws.com:5432/tinyurl
SPRING_DATASOURCE_USERNAME=<prod-db-user>
SPRING_DATASOURCE_PASSWORD=<generate: openssl rand -base64 32>
TINYURL_ALLOWED_ORIGINS=https://tinyurl.buffden.com
```

Note: no `POSTGRES_*` vars in `.env.prod` — there is no postgres container in prod.
The backend connects directly to RDS via `SPRING_DATASOURCE_URL`.

Add to `.gitignore`:

```plaintext
.env
.env.dev
.env.prod
```

---

### 1C. Add CORS to Spring Boot backend

The Angular SPA is served from `tinyurl.buffden.com` and calls
`https://go.buffden.com/api/` — a cross-origin request. Spring Boot needs
to allow this explicitly.

Add `allowedOrigins` to `AppProperties` record in `com.tinyurl.config`:

```java
@ConfigurationProperties(prefix = "tinyurl")
public record AppProperties(
    String baseUrl,
    Integer defaultExpiryDays,
    Integer shortCodeMinLength,
    String allowedOrigins
) {}
```

Add `WebConfig` in `com.tinyurl.config`:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties appProperties;

    public WebConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        String origins = appProperties.allowedOrigins();
        if (origins == null || origins.isBlank()) {
            return; // no cross-origin calls in dev (proxy.conf.json handles it)
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization")
                .maxAge(3600);
    }
}
```

Add the property to `application.yaml`:

```yaml
tinyurl:
  allowed-origins: ${TINYURL_ALLOWED_ORIGINS:}
```

No default needed — in dev the Angular proxy (`proxy.conf.json`) handles routing so the browser
never makes a cross-origin request. `TINYURL_ALLOWED_ORIGINS` is only required in production.

Add to `.env.prod` / `docker-compose.prod.yml` backend environment:

```bash
TINYURL_ALLOWED_ORIGINS=https://tinyurl.buffden.com
```

---

### 1D. Update Angular API URL

The Angular service currently calls `/api/urls` (relative path — assumes same origin).
In production the API is on a different subdomain, so it needs an absolute URL
driven by the Angular environment config.

**`tinyurl-gui/src/environments/environment.ts`** (dev):

```typescript
export const environment = {
  production: false,
  apiUrl: '/api'   // relative — proxied via proxy.conf.json → tinyurl-nginx in Docker
};
```

**`tinyurl-gui/src/environments/environment.prod.ts`** (prod):

```typescript
export const environment = {
  production: true,
  apiUrl: 'https://go.buffden.com/api'   // no proxy in prod, full URL required
};
```

Update **`url-shortener.service.ts`**:

```typescript
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class UrlShortenerService {
  private readonly apiUrl = `${environment.apiUrl}/urls`;

  shortenUrl(data: { url: string; expiresInDays?: number }): Observable<ShortenResponse> {
    return this.http.post<ShortenResponse>(this.apiUrl, data);
  }
}
```

---

### 1E. Update `.gitignore`

```plaintext
.env
.env.prod
```

---

## Phase 2: AWS Setup (S3 + CloudFront for SPA)

### 2A. Create S3 bucket

```bash
# Create bucket (pick a region close to your users)
aws s3api create-bucket \
  --bucket tinyurl-buffden-frontend \
  --region eu-central-1 \
  --create-bucket-configuration LocationConstraint=eu-central-1

# Disable Block Public Access (CloudFront needs to read it)
aws s3api put-public-access-block \
  --bucket tinyurl-buffden-frontend \
  --public-access-block-configuration \
    "BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false"

# Bucket policy — allow public read for CloudFront
aws s3api put-bucket-policy \
  --bucket tinyurl-buffden-frontend \
  --policy '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::tinyurl-buffden-frontend/*"
    }]
  }'
```

### 2B. Request ACM certificate (for HTTPS on CloudFront)

ACM certificates for CloudFront **must** be in `us-east-1` regardless of bucket region.

```bash
aws acm request-certificate \
  --domain-name tinyurl.buffden.com \
  --validation-method DNS \
  --region us-east-1
```

Add the DNS validation CNAME record to your domain registrar (shown in the ACM console),
then wait for status to become `ISSUED` (~5 minutes).

### 2C. Create CloudFront distribution

In the AWS Console (or CLI):

| Setting                        | Value                                            |
|:-------------------------------|:-------------------------------------------------|
| Origin domain                  | `tinyurl-buffden-frontend.s3.amazonaws.com`      |
| Origin path                    | *(empty)*                                        |
| Viewer protocol policy         | Redirect HTTP to HTTPS                           |
| Alternate domain name (CNAME)  | `tinyurl.buffden.com`                        |
| SSL certificate                | Select the ACM cert from 2B                      |
| Default root object            | `index.html`                                     |
| Price class                    | PriceClass_100 (US/Europe/Asia)                  |

**Custom error pages** — required for SPA client-side routing:

| HTTP error code | Response page path | HTTP response code |
|:----------------|:-------------------|:-------------------|
| 403             | `/index.html`      | 200                |
| 404             | `/index.html`      | 200                |

This ensures a hard refresh on any Angular route (e.g. `/dashboard`) still loads
the SPA instead of returning an S3 403/404.

### 2D. Add DNS record for the SPA subdomain

In your domain registrar:

| Type  | Name          | Value                                              |
|:------|:--------------|:---------------------------------------------------|
| CNAME | `app.tinyurl` | `<cloudfront-distribution-domain>.cloudfront.net`  |

### 2E. Build and deploy Angular SPA to S3

```bash
cd tinyurl-gui

# Production build
npx ng build --configuration production

# Sync dist to S3 (removes files no longer in dist)
aws s3 sync dist/tinyurl-gui/browser/ s3://tinyurl-buffden-frontend/ --delete

# Invalidate CloudFront cache after each deploy so users get fresh assets
aws cloudfront create-invalidation \
  --distribution-id <your-distribution-id> \
  --paths "/*"
```

> Add these three commands to your CI/CD pipeline for automated frontend deploys.

---

## Phase 3: Server Provisioning

**Recommended providers:**

| Provider     | Plan          | Specs           | Cost    |
|:-------------|:--------------|:----------------|:--------|
| Hetzner      | CX22          | 2 vCPU, 4GB RAM | ~€4/mo  |
| DigitalOcean | Basic Droplet | 1 vCPU, 2GB RAM | $6/mo   |
| AWS          | EC2 t3.small  | 2 vCPU, 2GB RAM | ~$15/mo |

**Minimum required: 2GB RAM** — Spring Boot JVM needs headroom alongside PostgreSQL.

**OS:** Ubuntu 24.04 LTS

```bash
# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
# Log out and back in to apply group

# Verify
docker --version
docker compose version
```

```bash
# Firewall
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 80/tcp    # HTTP (redirect to HTTPS)
sudo ufw allow 443/tcp   # HTTPS
sudo ufw enable
sudo ufw status
```

---

## Phase 4: DNS — Backend Subdomain

In your domain registrar:

| Type | Name      | Value              | TTL |
|:-----|:----------|:-------------------|:----|
| A    | `tinyurl` | `<your-server-ip>` | 300 |

> If using **Cloudflare**: set proxy to **DNS only (grey cloud)** initially so
> Certbot can issue the cert. You can enable the Cloudflare proxy afterwards.

Verify:

```bash
dig go.buffden.com +short   # should return your server IP
```

---

## Phase 5: SSL Certificate (Let's Encrypt)

Run this **before** starting Docker so Certbot can bind to port 80:

```bash
sudo apt install certbot -y

sudo certbot certonly --standalone \
  -d go.buffden.com \
  --email your@email.com \
  --agree-tos \
  --non-interactive

# Verify
ls /etc/letsencrypt/live/go.buffden.com/
# fullchain.pem  privkey.pem  chain.pem  cert.pem
```

Auto-renewal:

```bash
sudo systemctl enable certbot.timer
sudo systemctl start certbot.timer
```

Post-renewal hook — reload nginx so it picks up new certs:

```bash
echo "docker exec tinyurl-nginx nginx -s reload" | \
  sudo tee /etc/letsencrypt/renewal-hooks/post/reload-nginx.sh
sudo chmod +x /etc/letsencrypt/renewal-hooks/post/reload-nginx.sh
```

---

## Phase 6: Deploy Backend

```bash
# Clone the repo
git clone https://github.com/<your-org>/tinyurl.git
cd tinyurl

# Place production env file (scp from local or paste manually)
# scp .env.prod user@<server-ip>:~/tinyurl/.env.prod
cp .env.prod .env

# Build and start — nginx + backend + postgres only (no frontend container)
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  --env-file .env.prod up -d --build

# Watch startup logs
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f
```

Verify after ~60s:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
# NAME                STATUS
# tinyurl-nginx       running
# tinyurl-backend     running (healthy)
# tinyurl-postgres    running (healthy)

curl https://go.buffden.com/actuator/health
# {"status":"UP"}
```

---

## Phase 7: Deploy Frontend (S3)

Run these from your local machine (or CI runner):

```bash
cd tinyurl-gui

npx ng build --configuration production

aws s3 sync dist/tinyurl-gui/browser/ s3://tinyurl-buffden-frontend/ --delete

aws cloudfront create-invalidation \
  --distribution-id <your-distribution-id> \
  --paths "/*"
```

Verify: open `https://tinyurl.buffden.com` — Angular UI loads with HTTPS.

---

## Phase 8: Smoke Test

1. Open `https://tinyurl.buffden.com` — Angular UI loads, green lock visible
2. Shorten a URL — response contains `https://go.buffden.com/xxxxxx`
3. Visit the short URL — browser redirects to original URL
4. Confirm CORS works — no browser console errors on the API call
5. Test invalid short code `https://go.buffden.com/xxxxxx` — returns 404
6. Test rate limit — rapid POST to `/api/urls` should return 429 after burst

---

## Deployment Checklist

### Code changes

- [ ] `docker-compose.yml` slimmed to base (backend + postgres only)
- [ ] `docker-compose.override.yml` created (dev: nginx + frontend container)
- [ ] `docker-compose.prod.yml` created (prod: nginx + backend env — no frontend service)
- [ ] `.env.dev` created and committed
- [ ] `.env.prod` created locally, gitignored
- [ ] `.gitignore` updated
- [ ] `infra/nginx/nginx.prod.conf` updated — rate limiting + frontend proxy removed ✓
- [ ] CORS config added to Spring Boot backend
- [ ] Angular environment files updated with production API base URL
- [ ] `UrlShortenerService` updated to use `environment.apiUrl`

### AWS (frontend)

- [ ] S3 bucket created, public-read policy applied
- [ ] ACM certificate issued in `us-east-1` for `tinyurl.buffden.com`
- [ ] CloudFront distribution created, custom error pages set (403/404 → index.html)
- [ ] DNS CNAME `app.tinyurl` → CloudFront domain added

### VPS (backend)

- [ ] Server provisioned (2GB+ RAM, Ubuntu 24.04), Docker installed
- [ ] Firewall configured (ports 22, 80, 443)
- [ ] DNS A record `tinyurl` → server IP added, propagation confirmed
- [ ] Let's Encrypt cert issued, auto-renewal enabled, reload hook added
- [ ] Repo cloned, `.env.prod` placed as `.env`
- [ ] `docker compose ... up -d --build` — 3 services healthy

### Final

- [ ] Frontend deployed to S3, CloudFront invalidation run
- [ ] Smoke test passed end-to-end (UI loads, shorten works, redirect works, CORS clean)
