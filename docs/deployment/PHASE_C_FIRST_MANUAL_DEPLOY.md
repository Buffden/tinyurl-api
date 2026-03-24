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

# Build the production image
./gradlew bootJar
docker build -t ghcr.io/buffden/tinyurl-api:v1.0.0 .

# Push to GHCR
docker push ghcr.io/buffden/tinyurl-api:v1.0.0
```

> `GITHUB_TOKEN` — generate a Personal Access Token (PAT) at GitHub → Settings → Developer settings → Personal access tokens. Needs `write:packages` scope.
> After this, the image is at `ghcr.io/buffden/tinyurl-api:v1.0.0` and is private by default.

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

**Option B — Use AWS S3 as a transfer mechanism:**

```bash
# Local machine: upload files to S3
aws s3 cp docker-compose.prod.yml s3://tinyurl-spa-prod/deploy/docker-compose.prod.yml
aws s3 cp infra/nginx/nginx.prod.conf s3://tinyurl-spa-prod/deploy/nginx.prod.conf

# On EC2 (via SSM Session Manager):
aws s3 cp s3://tinyurl-spa-prod/deploy/docker-compose.prod.yml /app/docker-compose.prod.yml
mkdir -p /app/infra/nginx
aws s3 cp s3://tinyurl-spa-prod/deploy/nginx.prod.conf /app/infra/nginx/nginx.prod.conf
```

---

## Step 3 — Start Application on EC2

In the SSM Session Manager terminal on EC2:

```bash
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
- SSH alternative: use SSM Session Manager to check `curl http://localhost:8080/actuator/health`
- Check Docker logs: `docker compose -f docker-compose.prod.yml logs app`
- Confirm Nginx is running: `docker compose -f docker-compose.prod.yml ps`
- Confirm security group `sg-ec2` allows port 80 from `sg-alb`

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
aws s3 sync dist/browser/ s3://tinyurl-spa-prod/ --delete

# Verify upload
aws s3 ls s3://tinyurl-spa-prod/
# Should see index.html and hashed JS/CSS files
```

> `--delete` removes any stale files from previous builds.
> `dist/browser/` is the Angular 19 output path — verify with your actual build output.

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
| ALB health check unhealthy | App not started or crash on boot | Check `docker compose logs app` via SSM |
| CORS error in browser | `CorsConfig.java` not applied | Verify `application-prod.yaml` has correct origin |
| Short URL has wrong domain | Wrong SSM `base-url` | Update `/tinyurl/prod/base-url` → `https://go.buffden.com` |
| Angular can't reach API | Wrong `environment.prod.ts` | Verify `apiUrl: 'https://go.buffden.com/api'` |
| S3 returns 403 on SPA routes | Missing CloudFront error page config | Add 403 → `/index.html` error page in CloudFront |
| RDS connection refused | Wrong security group or wrong endpoint | Check `sg-rds` allows port 5432 from `sg-ec2` |

---

**Proceed to [Phase D](PHASE_D_CICD_AUTOMATION.md).**
