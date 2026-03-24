# Phase F — Hardening

**Goal:** Production is secure, protected against accidental deletion, and has a tested rollback path.
**Prerequisites:** Phase E complete
**Estimated time:** 1–2 hours

---

## Checklist

- [ ] Step 1 — Verify Nginx security headers
- [ ] Step 2 — Enable ALB access logs
- [ ] Step 3 — Enable AWS CloudTrail
- [ ] Step 4 — Enable EC2 termination protection
- [ ] Step 5 — Verify RDS deletion protection
- [ ] Step 6 — Take manual RDS snapshot
- [ ] Step 7 — Test rollback procedure
- [ ] Step 8 — Go-live verification checklist

---

## Step 1 — Verify Nginx Security Headers

These are already in `nginx.prod.conf` from Phase B. Verify they're being sent:

```bash
curl -I https://go.buffden.com | grep -E "X-Frame|X-Content|Referrer"
```

Expected output:
```
x-frame-options: DENY
x-content-type-options: nosniff
referrer-policy: strict-origin-when-cross-origin
```

If missing, check that `nginx.prod.conf` is mounted correctly:
```bash
# Via SSM Session Manager on EC2
docker compose -f /app/docker-compose.prod.yml exec nginx nginx -T | grep add_header
```

---

## Step 2 — Enable ALB Access Logs

ALB access logs record every request — useful for debugging and security audits.

1. Create an S3 bucket for logs: `tinyurl-alb-logs-prod` (in us-east-1)
2. Add S3 bucket policy to allow ALB to write (AWS requires this):
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Principal": {
           "AWS": "arn:aws:iam::127311923021:root"
         },
         "Action": "s3:PutObject",
         "Resource": "arn:aws:s3:::tinyurl-alb-logs-prod/alb/*"
       }
     ]
   }
   ```
   > `127311923021` is the AWS ELB service account for us-east-1 — this is correct, not a mistake.

3. Go to **EC2 → Load Balancers → tinyurl-alb → Attributes → Edit**
4. Access logs: **Enable**
5. S3 URI: `s3://tinyurl-alb-logs-prod/alb`
6. Save

---

## Step 3 — Enable AWS CloudTrail

CloudTrail records all AWS API calls — who did what, when.

1. Go to **CloudTrail → Trails → Create trail**
2. Trail name: `tinyurl-prod-trail`
3. Storage location: Create new S3 bucket `tinyurl-cloudtrail-prod`
4. Log file validation: **Enabled**
5. CloudWatch Logs: **Enabled**, log group: `/tinyurl/cloudtrail`
6. Management events: **Read and Write**
7. Click **Create trail**

> Free tier: first trail in a region is free. Only the S3 storage costs apply (~$0.50/month for minimal traffic).

---

## Step 4 — EC2 Termination Protection

Prevents accidentally terminating the EC2 instance from the console or CLI.

1. Go to **EC2 → Instances → tinyurl-prod**
2. **Actions → Instance settings → Change termination protection**
3. Enable: **Yes**
4. Save

> To actually terminate the instance, you must first disable termination protection — a safeguard against accidents.

---

## Step 5 — Verify RDS Deletion Protection

Was enabled during Phase A. Verify:

1. Go to **RDS → Databases → tinyurl-prod**
2. **Configuration** tab → Deletion protection: **Enabled**

If not enabled:
1. **Modify → Deletion protection → Enable → Apply immediately**

---

## Step 6 — Manual RDS Snapshot Before Go-Live

Take a manual snapshot as a safety baseline before going live.

1. Go to **RDS → Databases → tinyurl-prod**
2. **Actions → Take snapshot**
3. Snapshot identifier: `tinyurl-prod-pre-golive-v1`
4. Click **Take snapshot** — takes 2–5 minutes

> Automated backups run daily (7-day retention). Manual snapshots are kept until you delete them — this is your permanent baseline.

---

## Step 7 — Rollback Procedures

Test these **before** going live so you know they work.

### Backend rollback (previous Docker image)

```bash
# 1. Find the previous image SHA from GitHub Actions history
#    GitHub → repository → Actions → last successful deploy → copy the git SHA

# 2. Connect to EC2 via SSM Session Manager

# 3. Roll back to previous image
cd /app
export IMAGE_TAG=<previous-git-sha>
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d

# 4. Verify health
curl http://localhost/actuator/health
```

### Frontend rollback

There's no automatic versioning in the S3 bucket for v1. Options:

**Option A — Redeploy from Git:**
```bash
git checkout <previous-commit>
ng build --configuration=production
aws s3 sync dist/browser/ s3://tinyurl-spa-prod/ --delete
aws cloudfront create-invalidation --distribution-id <dist-id> --paths "/*"
```

**Option B — Enable S3 versioning (recommended for v2):**
Turn on versioning on `tinyurl-spa-prod` bucket — allows restoring any previous file version.

### Database rollback (emergency only)

Restoring RDS from a snapshot replaces the entire database — use only if data is corrupted.

```bash
# 1. Go to RDS → Snapshots → select the snapshot
# 2. Actions → Restore snapshot
# 3. New DB identifier: tinyurl-prod-restored
# 4. Update /tinyurl/prod/db/url in SSM Parameter Store to point to new endpoint
# 5. Restart app on EC2:
docker compose -f /app/docker-compose.prod.yml restart app
```

---

## Step 8 — Go-Live Verification Checklist

Run this full checklist before announcing the app publicly.

**Security:**
- [ ] `https://tinyurl.buffden.com` loads Angular SPA over HTTPS
- [ ] `http://tinyurl.buffden.com` redirects to `https://`
- [ ] `https://go.buffden.com` responds (not 404)
- [ ] `http://go.buffden.com` redirects to `https://`
- [ ] `https://go.buffden.com/actuator/health` returns **403** (blocked by Nginx — not exposed publicly)
- [ ] No CORS errors in browser DevTools when using the form
- [ ] Security headers present: `X-Frame-Options`, `X-Content-Type-Options`

**Functionality:**
- [ ] URL creation returns `https://go.buffden.com/{code}`
- [ ] Short URL redirects to original URL (301 for no-expiry, 302 for explicit expiry)
- [ ] Expired link returns 410 Gone
- [ ] Invalid short code returns 404
- [ ] QR code displays correctly
- [ ] Copy-to-clipboard works

**Infrastructure:**
- [ ] ALB target group health check: **Healthy**
- [ ] RDS status: **Available**
- [ ] CloudWatch alarms: all in **OK** state
- [ ] CloudWatch logs: `/tinyurl/prod/app` receiving logs
- [ ] Manual RDS snapshot exists: `tinyurl-prod-pre-golive-v1`

**CI/CD:**
- [ ] Last deploy pipeline succeeded on `main`
- [ ] Rollback procedure tested and documented

---

## v1 is Live — What's Next (v2 Preview)

Once v1 is stable and receiving real traffic, the v2 roadmap adds:

| Feature | What it unlocks |
|---|---|
| Redis cache-aside (Docker on same EC2) | Redirects served from memory, DB load drops significantly |
| Redis rate limiting (token bucket) | Per-IP rate limiting moves out of Nginx into application layer |
| RDS Multi-AZ | High availability — no downtime for DB maintenance |
| Terraform | Reproducible infrastructure — tear down and recreate in minutes |
| CloudFront on `go.buffden.com` | Edge caching for popular redirects (only if traffic justifies it) |
| Custom aliases | Users can choose their own short code |
