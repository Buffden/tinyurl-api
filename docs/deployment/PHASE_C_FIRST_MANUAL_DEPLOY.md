# Phase C — First Manual Deploy

**Goal:** Application running end-to-end in production for the first time. All steps are manual — CI/CD comes in Phase D.
**Prerequisites:** Phase A and Phase B complete
**Estimated time:** 1 hour

---

## Checklist

- [ ] Step 1 — Build and push Docker image to GHCR
- [ ] Step 2 — Copy files to EC2 via SSM
- [ ] Step 3 — Start application on EC2
- [ ] Step 4 — Verify ALB health check
- [ ] Step 5 — Build and upload Angular to S3
- [ ] Step 6 — Invalidate CloudFront cache
- [ ] Step 7 — Full end-to-end smoke test

---

## Step 1 — Build and Push Docker Image to GHCR

Run from the root of the backend repo on your local machine.

```bash
# Authenticate to GitHub Container Registry
echo $GITHUB_TOKEN | docker login ghcr.io -u buffden --password-stdin

# Build the production image — IMPORTANT: specify linux/amd64 platform
# EC2 is x86_64. If you build on Apple Silicon (M1/M2/M3) without this flag,
# the image will be arm64 and fail to start on EC2 with "no matching manifest" error.
./gradlew bootJar
docker buildx build --platform linux/amd64 -t ghcr.io/buffden/tinyurl-api:v1.0.0 --push .
```

> `GITHUB_TOKEN` — generate a Personal Access Token (PAT) at GitHub → Settings → Developer settings → Personal access tokens. Needs `write:packages` scope.
> After this, the image is at `ghcr.io/buffden/tinyurl-api:v1.0.0` and is private by default.
> Note: `docker buildx build --push` builds and pushes in one step. No separate `docker push` needed.

**Make the package visible to EC2:**

GitHub packages are private by default. EC2 needs to pull this image. Two options:
- Make the package public (simplest for v1): GitHub → your profile → Packages → `tinyurl-api` → Package settings → Change visibility → Public
- Or configure a PAT on EC2 for `docker login` before pulling (more secure)

For v1, making it public is fine since it's just a compiled JAR in a Docker image — no secrets inside.

---

## Step 2 — Copy Files to EC2 via SSM

You need to get `docker-compose.prod.yml` and `nginx.prod.conf` onto the EC2 instance.

**Option A — Copy content via SSM Session Manager (easiest):**

1. Go to **AWS Console → Systems Manager → Session Manager → Start session**
2. Select your `tinyurl-prod` EC2 instance
3. Click **Start session** — a browser terminal opens

In the terminal:

```bash
# Switch to ubuntu user
sudo su - ubuntu

# Fix /app ownership if needed (user data script runs as root, so ubuntu can't write)
sudo chown -R ubuntu:ubuntu /app

# Create app directory
mkdir -p /app/infra/nginx

# Create docker-compose.prod.yml
cat > /app/docker-compose.prod.yml << 'EOF'
# paste the contents of docker-compose.prod.yml here
EOF

# Create nginx.prod.conf
cat > /app/infra/nginx/nginx.prod.conf << 'EOF'
# paste the contents of nginx.prod.conf here
EOF

# Set the RDS endpoint env var (replace with your actual RDS endpoint)
echo 'export RDS_ENDPOINT=tinyurl-prod.xyz.us-east-1.rds.amazonaws.com' >> ~/.bashrc
source ~/.bashrc
```

**Option B — Use AWS S3 as a transfer mechanism (recommended):**

SSM browser terminal mangles multi-line heredoc pastes (characters get dropped/reordered), making Option A unreliable for large files. Use S3 instead.

```bash
# Local machine: upload files to S3 (run from repo root)
aws s3 cp docker-compose.prod.yml s3://tinyurl-spa-prod/deploy/docker-compose.prod.yml
aws s3 cp infra/nginx/nginx.prod.conf s3://tinyurl-spa-prod/deploy/nginx.prod.conf

# On EC2 (via SSM Session Manager):
# Install AWS CLI if not present
sudo apt-get install -y awscli

mkdir -p /app/infra/nginx
aws s3 cp s3://tinyurl-spa-prod/deploy/docker-compose.prod.yml /app/docker-compose.prod.yml
aws s3 cp s3://tinyurl-spa-prod/deploy/nginx.prod.conf /app/infra/nginx/nginx.prod.conf
```

> If `aws s3 cp` returns 403 Forbidden, the EC2 IAM role is missing S3 permissions.
> Fix: AWS Console → IAM → Roles → `role-tinyurl-ec2` → Attach `AmazonS3ReadOnlyAccess`.

---

## Step 3 — Start Application on EC2

In the SSM Session Manager terminal on EC2:

```bash
# Install Docker if user data script didn't run (check with: docker --version)
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2
sudo usermod -aG docker ubuntu
newgrp docker   # apply group without logout

cd /app

# Set image tag
export IMAGE_TAG=v1.0.0

# Pull the image
docker compose -f docker-compose.prod.yml pull

# Start services (detached)
docker compose -f docker-compose.prod.yml up -d

# Watch logs for startup errors
docker compose -f docker-compose.prod.yml logs -f app
```

Watch for these log lines indicating successful startup:
- `Started TinyUrlApplication in X seconds`
- `Flyway migrations completed`
- No `ERROR` or `FATAL` lines

**If the app crashes with `Could not resolve placeholder 'tinyurl.cors.allowed-origins'`:**

`@Value` cannot inject a YAML list property. The `application-prod.yaml` CORS config must use a comma-separated string, not YAML list syntax:

```yaml
# WRONG — causes placeholder resolution failure
tinyurl:
  cors:
    allowed-origins:
      - "https://tinyurl.buffden.com"

# CORRECT
tinyurl:
  cors:
    allowed-origins: "https://tinyurl.buffden.com"
```

**If Docker logs show `AccessDeniedException` for CloudWatch Logs:**

The EC2 role is missing CloudWatch permissions. Fix: AWS Console → IAM → Roles → `role-tinyurl-ec2` → Attach `CloudWatchLogsFullAccess`. Then restart:

```bash
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml up -d
```

If you see SSM Parameter Store errors, check:
- Parameters exist with correct names in `/tinyurl/prod/`
- EC2 IAM role has SSM read permission
- `spring.config.import` path matches

---

## Step 4 — Verify ALB Health Check

1. Go to **EC2 → Target Groups → tg-tinyurl-api**
2. Click **Targets** tab
3. Wait for the registered EC2 to show **Healthy** status (can take up to 60s)

If it stays **Unhealthy:**

- Use SSM Session Manager to check: `curl http://localhost/actuator/health`
  - If this returns `{"status":"UP"}` the app is fine — the issue is between Nginx and the health check path
  - If this returns 403, Nginx is blocking `/actuator/health` (see fix below)
  - If connection refused, the app hasn't started — check `docker compose -f docker-compose.prod.yml logs app`
- Confirm Nginx is running: `docker compose -f docker-compose.prod.yml ps`
- Confirm security group `sg-ec2` allows port 80 from `sg-alb`

**Critical: ALB targets port 80 on EC2, which goes through Nginx — not directly to the app.**

If Nginx has a blanket `location /actuator/ { return 403; }` block, health checks will return 403 and the target will stay Unhealthy. The fix is an exact-match location *before* the blanket block in `nginx.prod.conf`:

```nginx
# Allow ALB health checks through — MUST be before the blanket /actuator/ block
location = /actuator/health {
    proxy_pass http://app:8080;
    proxy_set_header Host $host;
}

# Block all other actuator endpoints
location /actuator/ {
    return 403;
}
```

After updating the nginx config, re-upload via S3 and restart the nginx container:

```bash
aws s3 cp s3://tinyurl-spa-prod/deploy/nginx.prod.conf /app/infra/nginx/nginx.prod.conf
docker compose -f docker-compose.prod.yml restart nginx
```

---

## Step 5 — Build and Upload Angular to S3

Run on your local machine from the `tinyurl-gui` repo:

```bash
# Build for production
npm ci
ng build --configuration=production

# Verify the API URL is correct before uploading
grep -r "go.buffden.com" dist/
# Should find it in the compiled JS

# Upload to S3
aws s3 sync dist/tinyurl-gui/browser/ s3://tinyurl-spa-prod/ --delete

# Verify upload
aws s3 ls s3://tinyurl-spa-prod/
# Should see index.html and hashed JS/CSS files
```

> `--delete` removes any stale files from previous builds.
> Output path is `dist/tinyurl-gui/browser/` — the project name comes from `outputPath` in angular.json.

**If `ng build` fails with `NG0401` during route extraction:**

This is an Angular 19.2 SSR breaking change. The `main.server.ts` bootstrap function must accept and forward `BootstrapContext`:

```typescript
// src/main.server.ts — CORRECT for Angular 19.2+
import { bootstrapApplication, BootstrapContext } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { config } from './app/app.config.server';

const bootstrap = (context: BootstrapContext) => bootstrapApplication(AppComponent, config, context);

export default bootstrap;
```

Without the `BootstrapContext` parameter, Angular's server platform cannot initialize and throws NG0401 for every prerender attempt.

**Additional Angular 19 SSR requirements** (all must be in place):

- `app.config.ts` must include `provideAnimationsAsync()` — Angular Material components (`MatTabsModule`, `MatExpansionModule`) require the `ANIMATION_MODULE_TYPE` token to be provided
- `app.config.server.ts` must include `provideServerRoutesConfig(serverRoutes)` and `provideNoopAnimations()`
- `app.routes.server.ts` must exist and define render modes using `RenderMode` from `@angular/ssr` — the old `data: { prerender: true }` route property is deprecated and causes NG0401

---

## Step 6 — Invalidate CloudFront Cache

```bash
# Get your distribution ID
aws cloudfront list-distributions --query "DistributionList.Items[?Aliases.Items[0]=='tinyurl.buffden.com'].Id" --output text

# Invalidate all cached files
aws cloudfront create-invalidation \
  --distribution-id <your-dist-id> \
  --paths "/*"
```

Wait 30–60 seconds for the invalidation to complete. Check status:
```bash
aws cloudfront list-invalidations --distribution-id <your-dist-id>
# Status should change from InProgress to Completed
```

---

## Step 7 — Full End-to-End Smoke Test

Run these checks in order:

```bash
# 1. Backend health
curl https://go.buffden.com/actuator/health
# Expected: {"status":"UP","components":{"db":{"status":"UP"},...}}

# 2. HTTP → HTTPS redirect
curl -I http://go.buffden.com
# Expected: HTTP/1.1 301, Location: https://go.buffden.com/

# 3. Create a short URL
curl -X POST https://go.buffden.com/api/urls \
  -H "Content-Type: application/json" \
  -d '{"url": "https://www.google.com"}' | jq .
# Expected: {"shortUrl":"https://go.buffden.com/xK9mP2","shortCode":"xK9mP2",...}

# 4. Follow the short URL (copy shortCode from step 3)
curl -I https://go.buffden.com/<shortCode>
# Expected: HTTP/1.1 301, Location: https://www.google.com

# 5. Angular SPA loads
curl -I https://tinyurl.buffden.com
# Expected: HTTP/2 200, content-type: text/html
```

**Browser test:**
1. Open `https://tinyurl.buffden.com` — Angular form should load
2. Enter a URL, click shorten — should return `https://go.buffden.com/{code}`
3. Open browser DevTools → Network tab — verify no CORS errors on the API call
4. Click the short URL — should redirect to the original URL

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `no matching manifest for linux/amd64` on EC2 | Image built on Apple Silicon without platform flag | Rebuild with `docker buildx build --platform linux/amd64` |
| `docker: command not found` on EC2 | User data script didn't run | `sudo apt-get install -y docker.io docker-compose-v2` |
| `aws s3 cp` returns 403 Forbidden | EC2 role missing S3 permissions | Attach `AmazonS3ReadOnlyAccess` to `role-tinyurl-ec2` |
| CloudWatch `AccessDeniedException` in Docker logs | EC2 role missing CloudWatch permissions | Attach `CloudWatchLogsFullAccess` to `role-tinyurl-ec2` |
| App crash: `Could not resolve placeholder 'tinyurl.cors.allowed-origins'` | CORS config uses YAML list syntax; `@Value` requires a string | Change `allowed-origins` to comma-separated string in `application-prod.yaml` |
| ALB health check stays Unhealthy with 403 | Nginx blocks `/actuator/` before ALB can reach app | Add `location = /actuator/health` exact-match block before the blanket `location /actuator/` block in nginx config |
| `ng build` fails with NG0401 | `main.server.ts` missing `BootstrapContext` parameter (Angular 19.2+) | Update bootstrap function signature — see Step 5 |
| CORS error in browser | `CorsConfig.java` not applied | Verify `application-prod.yaml` has correct origin as comma-separated string |
| Short URL has wrong domain | Wrong SSM `base-url` | Update `/tinyurl/prod/base-url` → `https://go.buffden.com` |
| Angular can't reach API | Wrong `environment.prod.ts` | Verify `apiUrl: 'https://go.buffden.com/api'` |
| S3 returns 403 on SPA routes | Missing CloudFront error page config | Add 403 → `/index.html` error page in CloudFront |
| RDS connection refused | Wrong security group or wrong endpoint | Check `sg-rds` allows port 5432 from `sg-ec2` |

---

**Proceed to [Phase D](PHASE_D_CICD_AUTOMATION.md).**
