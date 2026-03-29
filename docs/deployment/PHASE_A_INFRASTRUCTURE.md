# Phase A — Infrastructure Provisioning

**Goal:** All AWS resources exist and are wired together. No application deployed yet.
**Method:** Manual AWS Console (Terraform deferred to v2)
**Region:** `us-east-1` (N. Virginia) for everything
**Estimated time:** 2–3 hours in the console

**Deliverable:** `curl https://go.buffden.com` returns HTTP 502 — ALB is up, EC2 is registered, but no app running yet. That 502 confirms the full network path is working.

---

## Checklist

- [ ] Step 1 — Route 53 hosted zone + DNS delegation
- [ ] Step 2 — ACM wildcard certificate
- [ ] Step 3 — VPC and networking
- [ ] Step 4 — Security groups
- [ ] Step 5 — IAM roles
- [ ] Step 6 — RDS PostgreSQL
- [ ] Step 7 — EC2 instance
- [ ] Step 8 — ALB + target group
- [ ] Step 9 — S3 bucket
- [ ] Step 10 — CloudFront distribution
- [ ] Step 11 — Route 53 DNS records
- [ ] Step 12 — Cloudflare DNS migration (free DDoS + edge protection)

---

## Step 1 — Route 53 Hosted Zone + DNS Delegation

### 1a. Create hosted zone in Route 53

1. Go to **Route 53 → Hosted zones → Create hosted zone**
2. Domain name: `buffden.com`
3. Type: Public hosted zone
4. Click **Create hosted zone**
5. Copy the 4 NS records shown (e.g. `ns-123.awsdns-45.com`, etc.)

### 1b. Delegate from Namecheap to Route 53

1. Log in to Namecheap → Domain List → `buffden.com` → Manage
2. Go to **Nameservers** section
3. Select **Custom DNS**
4. Paste the 4 Route 53 NS records (one per field)
5. Save

> Propagation takes up to 48 hours but is usually done in under 1 hour.
> Verify with: `nslookup -type=NS buffden.com 8.8.8.8`
> You should see your Route 53 nameservers in the response.

---

## Step 2 — ACM Wildcard Certificate

A single wildcard cert covers both `tinyurl.buffden.com` and `go.buffden.com`.

1. Go to **ACM (Certificate Manager)** — confirm you are in **us-east-1**
2. Click **Request certificate → Request a public certificate**
3. Domain names: `*.buffden.com`
4. Validation method: **DNS validation**
5. Click **Request**
6. On the certificate detail page, click **Create records in Route 53** (one click — auto-adds the CNAME validation record)
7. Wait ~2 minutes for status to change from **Pending validation** to **Issued**

> ACM certificates auto-renew — you never need to touch this again.

---

## Step 3 — VPC and Networking

### 3a. Create VPC

1. Go to **VPC → Your VPCs → Create VPC**
2. Name: `tinyurl-prod-vpc`
3. IPv4 CIDR: `10.0.0.0/16`
4. Click **Create VPC**

### 3b. Create subnets

**Public subnets (ALB + EC2):**

| Name | AZ | CIDR |
|---|---|---|
| `tinyurl-public-1a` | us-east-1a | `10.0.1.0/24` |
| `tinyurl-public-1b` | us-east-1b | `10.0.2.0/24` |

**Private subnets (RDS):**

| Name | AZ | CIDR |
|---|---|---|
| `tinyurl-private-1a` | us-east-1a | `10.0.3.0/24` |
| `tinyurl-private-1b` | us-east-1b | `10.0.4.0/24` |

For each subnet:
1. Go to **VPC → Subnets → Create subnet**
2. Select `tinyurl-prod-vpc`
3. Fill in name, AZ, CIDR as above

> ALB requires subnets in at least 2 AZs — that's why we create two public subnets even with one EC2.

### 3c. Internet Gateway

1. Go to **VPC → Internet Gateways → Create internet gateway**
2. Name: `tinyurl-igw`
3. Click **Create**, then **Actions → Attach to VPC → tinyurl-prod-vpc**

### 3d. Route table for public subnets

1. Go to **VPC → Route Tables → Create route table**
2. Name: `tinyurl-public-rt`, VPC: `tinyurl-prod-vpc`
3. After creating, click **Edit routes → Add route**:
   - Destination: `0.0.0.0/0`
   - Target: `tinyurl-igw` (Internet Gateway)
4. Click **Subnet associations → Edit → associate both public subnets**

> Private subnets use the default route table (no internet route) — RDS stays unreachable from outside.

---

## Step 4 — Security Groups

Create three security groups inside `tinyurl-prod-vpc`.

### tinyurl-alb (Internet-facing load balancer)

> AWS does not allow security group names starting with `sg-` — use names without that prefix.

1. **VPC → Security Groups → Create security group**
2. Name: `tinyurl-alb`, VPC: `tinyurl-prod-vpc`
3. Description: `Internet-facing load balancer`
4. Inbound rules:
   - HTTP (80) from `0.0.0.0/0`
   - HTTPS (443) from `0.0.0.0/0`
5. Outbound: All traffic (default)

### tinyurl-ec2 (Application server)

1. Name: `tinyurl-ec2`, VPC: `tinyurl-prod-vpc`
2. Description: `Application server, accepts traffic from ALB only`
3. Inbound rules:
   - HTTP (80) from source: `tinyurl-alb` (not `0.0.0.0/0` — only ALB can reach EC2)
4. Outbound: All traffic (default)

> No port 22. SSH is not used. EC2 access is via SSM Session Manager only.

### tinyurl-rds (Database)

1. Name: `tinyurl-rds`, VPC: `tinyurl-prod-vpc`
2. Description: `Database, accepts traffic from EC2 only`
3. Inbound rules:
   - PostgreSQL (5432) from source: `tinyurl-ec2`
4. Outbound: None (remove default rule)

---

## Step 5 — IAM Roles

### Role 1: EC2 instance role

This role lets EC2 use SSM Session Manager (replaces SSH) and read secrets from SSM Parameter Store.

1. Go to **IAM → Roles → Create role**
2. Trusted entity: **AWS service → EC2**
3. Attach policy: `AmazonSSMManagedInstanceCore` (AWS managed — enables SSM)
4. Name: `role-tinyurl-ec2`
5. After creating, add an inline policy named `tinyurl-ssm-params`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameter",
        "ssm:GetParameters",
        "ssm:GetParametersByPath"
      ],
      "Resource": "arn:aws:ssm:us-east-1:*:parameter/tinyurl/prod/*"
    }
  ]
}
```

### Role 2: GitHub Actions OIDC role

This role lets GitHub Actions deploy without storing any AWS keys.

**2a. Create OIDC provider:**

> Skip this step if the provider already exists. Go to **IAM → Identity providers** and check if `token.actions.githubusercontent.com` is already listed (likely from a previous project). If it is, proceed directly to 2b.

1. Go to **IAM → Identity providers → Add provider**
2. Provider type: **OpenID Connect**
3. Provider URL: `https://token.actions.githubusercontent.com`
4. Click **Get thumbprint**
5. Audience: `sts.amazonaws.com`
6. Click **Add provider**

**2b. Create the role:**

1. **IAM → Roles → Create role**
2. Trusted entity: **Web identity**
3. Identity provider: `token.actions.githubusercontent.com`
4. Audience: `sts.amazonaws.com`
5. Name: `role-github-actions-tinyurl`
6. Attach these AWS managed policies:
   - `AmazonS3FullAccess` (scoped to tinyurl bucket — or use inline policy below)
   - `CloudFrontFullAccess`
   - `AmazonSSMFullAccess` (for RunCommand to EC2)
7. After creating, edit the trust policy to scope it to your repos only:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<account-id>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringLike": {
          "token.actions.githubusercontent.com:sub": [
            "repo:Buffden/tinyurl-api:*",
            "repo:Buffden/tinyurl-gui:*"
          ]
        },
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        }
      }
    }
  ]
}
```

> Replace `<account-id>` with your 12-digit AWS account ID.
> The `*` wildcard allows any branch and trigger type (push, workflow_dispatch, etc.) for both repos.
> If you want to lock down to `main` only after testing, replace `*` with `ref:refs/heads/main`.

---

## Step 6 — RDS PostgreSQL

### 6a. Create DB subnet group

1. Go to **RDS → Subnet groups → Create DB subnet group**
2. Name: `tinyurl-rds-subnet-group`
3. VPC: `tinyurl-prod-vpc`
4. Add subnets: both private subnets (`10.0.3.0/24` and `10.0.4.0/24`)

### 6b. Create RDS instance

1. Go to **RDS → Databases → Create database**
2. Engine: **PostgreSQL**, version: **17.x** (latest 17)
3. Template: **Free Tier** (visible for standard PostgreSQL — you will still be billed since account free tier has expired, but this template forces db.t3.micro)
4. DB instance identifier: `tinyurl-prod`
5. Master username: `tinyurl`
6. Master password: **Self managed** — generate with `openssl rand -base64 32`, save it, you will put it in SSM Parameter Store in Phase B
7. Database authentication: **Password authentication**
8. Instance class: `db.t3.micro` (selected automatically by Free Tier template)
9. Multi-AZ: **Disable** (not needed for this project, saves ~$13/month)
10. Storage: 25 GiB gp3, disable auto-scaling (minimum enforced by AWS is 20 GiB)
11. Connectivity: **Don't connect to an EC2 compute resource** (EC2 not created yet — connectivity handled via security groups)
12. Network type: **IPv4**
13. VPC: `tinyurl-prod-vpc`
14. DB subnet group: `tinyurl-rds-subnet-group`
15. Public access: **No**
16. VPC security group: `tinyurl-rds`
17. Initial database name: `tinyurl_production_db`
18. Performance Insights: **Enable**, retention **7 days** (free tier) — or disable entirely
19. Certificate authority: `rds-ca-rsa2048-g1` (default — free, enables TLS)
20. Automated backups: Enabled, 7-day retention
21. Encryption: Enabled (AWS managed key)
22. Deletion protection: **Enabled**
23. Click **Create database** — takes ~5 minutes

> After creation, copy the **Endpoint** (e.g. `tinyurl-prod.xyz.us-east-1.rds.amazonaws.com`). You will need it for SSM Parameter Store in Phase B.

---

## Step 7 — EC2 Instance

1. Go to **EC2 → Launch instance**
2. Name: `tinyurl-prod`
3. AMI: **Ubuntu Server 22.04 LTS** (64-bit x86)
4. Instance type: `t3.small` (~$15/month — 2 GB RAM needed to run Docker + Spring Boot + Nginx without OOM)
5. Key pair: **Proceed without a key pair** (access is via SSM — no SSH needed)
6. Network settings:
   - VPC: `tinyurl-prod-vpc`
   - Subnet: **`tinyurl-public-1a`** (`10.0.1.0/24`) — must be a public subnet, not private
   - Auto-assign public IP: **Enable**
   - Security group: `tinyurl-ec2`

> **Critical:** Make sure the subnet selected shows CIDR `10.0.1.0/24`. Using a private subnet (`10.0.3.x`) will prevent SSM from reaching AWS endpoints and the instance will not appear in Fleet Manager.
7. Storage: 20 GB gp3
8. Advanced details → IAM instance profile: `role-tinyurl-ec2`
9. User data (paste this — installs Docker on first boot):

```bash
#!/bin/bash
apt-get update -y
apt-get install -y docker.io docker-compose-plugin
systemctl enable docker
systemctl start docker
usermod -aG docker ubuntu
mkdir -p /app
```

10. Click **Launch instance**

> After launch, go to **Systems Manager → Fleet Manager**. Within 2–3 minutes the instance should appear as **Online**. This confirms SSM Session Manager is working and you can connect without SSH.
>
> **Why SSM matters:** No SSH or key pair needed — access is via AWS console. GitHub Actions uses SSM `SendCommand` in Phase D to trigger deployments on the EC2 instance. Without SSM working, automated deployments will not work.

---

## Step 8 — ALB + Target Group

### 8a. Create target group

1. Go to **EC2 → Target Groups → Create target group**
2. Target type: **Instances**
3. Name: `tg-tinyurl-api`
4. Protocol: HTTP, Port: 80
5. VPC: `tinyurl-prod-vpc`
6. Health check:
   - Protocol: HTTP
   - Path: `/actuator/health`
   - Healthy threshold: 2
   - Unhealthy threshold: 3
   - Interval: 30s
   - Timeout: 5s
   - Success codes: 200
7. Click **Next**, select your EC2 instance, click **Include as pending**, then **Create target group**

### 8b. Create load balancer

1. Go to **EC2 → Load Balancers → Create load balancer → Application Load Balancer**
2. Name: `tinyurl-alb`
3. Scheme: **Internet-facing**
4. IP address type: IPv4
5. VPC: `tinyurl-prod-vpc`
6. Subnets: select both public subnets (`tinyurl-public-1a`, `tinyurl-public-1b`)
7. Security groups: `tinyurl-alb`
8. Listeners:
   - Port 80: **Add listener** → Action: Redirect to HTTPS (443), status 301
   - Port 443: **Add listener** → Action: Forward to `tg-tinyurl-api`
   - For port 443, select ACM certificate: `*.buffden.com`
9. Click **Create load balancer**

> Copy the ALB **DNS name** (e.g. `tinyurl-alb-123456789.us-east-1.elb.amazonaws.com`). You'll need it for Route 53 in Step 11.

---

## Step 9 — S3 Bucket

1. Go to **S3 → Create bucket**
2. Bucket name: `tinyurl-spa-prod`
3. Region: `us-east-1`
4. Bucket type: **General purpose**
5. Object ownership: **ACLs disabled** (recommended)
6. Block all public access: **On** (all four checkboxes)
7. Versioning: **Disabled**
8. Encryption: **SSE-S3** (default), Bucket Key: **Enable**
9. Click **Create bucket**

> Do not enable static website hosting — CloudFront handles routing.
> The S3 bucket policy will be automatically added by CloudFront when you select "Allow private S3 bucket access" during distribution creation.

---

## Step 10 — CloudFront Distribution

1. Go to **CloudFront → Create distribution**
   - If a pricing plan popup appears, select **Pay-as-you-go** (no commitment, costs under $1/month for a portfolio project — AWS Shield Standard DDoS protection is included free regardless)
2. Distribution name: `tinyurl-spa-prod`
3. Distribution type: **Single website or app**
4. Route 53 managed domain: leave blank
5. Origin type: **Amazon S3**
6. S3 origin: `tinyurl-spa-prod.s3.us-east-1.amazonaws.com`
7. Origin path: leave blank
8. Allow private S3 bucket access: **Allow private S3 bucket access to CloudFront (Recommended)** — CloudFront will automatically update the S3 bucket policy
9. Origin settings: **Use recommended origin settings**
10. Cache settings: **Use recommended cache settings tailored to serving S3 content**
11. WAF (Enable security protections): **Do not enable security protections** — $14/month not worth it for a portfolio project. AWS Shield Standard DDoS protection is free and automatic.
12. After creation, go to **Settings → Edit** and configure:
    - **Alternate domain names**: `tinyurl.buffden.com`
    - **Custom SSL certificate**: `*.buffden.com`
    - **Default root object**: `index.html`
    - **Price class**: **Use only North America and Europe**
    - **IPv6**: **On** (free, no downside)
    - Click **Save changes**

**Add custom error pages (required for Angular routing):**

1. CloudFront → your distribution → **Error pages tab → Create custom error response**
2. HTTP error code: **403**
   - Customize error response: **Yes**
   - Response page path: `/index.html`
   - HTTP response code: **200**
   - Click **Create**
3. Repeat exactly the same for HTTP error code: **404**

> These error pages are critical. Without them, refreshing any Angular route (e.g. `/dashboard`) will return a 403/404 from S3 instead of serving the SPA.

> CloudFront automatically updates the S3 bucket policy when you select "Allow private S3 bucket access" — no manual bucket policy update needed.

---

## Step 11 — Route 53 DNS Records

1. Go to **Route 53 → Hosted zones → buffden.com → Create record**

**Record 1 — Angular SPA:**
- Record name: `tinyurl`
- Record type: **A**
- Alias: **Yes**
- Route traffic to: **Alias to CloudFront distribution**
- Select your CloudFront distribution
- Click **Create record**

**Record 2 — API + redirects:**
- Record name: `go`
- Record type: **A**
- Alias: **Yes**
- Route traffic to: **Alias to Application and Classic Load Balancer**
- Region: us-east-1
- Select `tinyurl-alb`
- Click **Create record**

---

## Step 12 — Cloudflare DNS Migration

Moves DNS from Route 53 to Cloudflare so flood attacks are absorbed at Cloudflare's edge before CloudFront or ALB ever see the traffic — eliminating the billing impact of DDoS attacks. All AWS services stay completely unchanged.

### 12a. Sign up and add domain

1. Go to **cloudflare.com → Sign up → Free plan**
2. Click **Add a Site → `buffden.com` → Free plan**
3. Select **Import DNS records automatically** — Cloudflare scans Route 53 records

### 12b. Fix imported DNS records

Cloudflare's auto-import is incomplete. Manually verify and correct all records:

| Type | Name | Content | Proxy |
| --- | --- | --- | --- |
| CNAME | `go` | `dualstack.tinyurl-alb-xxx.us-east-1.elb.amazonaws.com` | Orange (Proxied) |
| CNAME | `tinyurl` | `d1anlbbmfo4elu.cloudfront.net` | Orange (Proxied) |
| A | `ems` | `100.25.10.178` | Orange (Proxied) |
| CNAME | `portfolio` | `buffden.github.io` | Grey (DNS only) |
| CNAME | `_2a3ec5a40d53220a744f6e248e46f22b` | `_bf955b229028da621bc467ed086b9ddc.jkddzztszm.acm-validations.aws` | Grey (DNS only) |

> The `go` record is likely imported as two raw A records (IPs). Delete them and add a single CNAME pointing to the ALB hostname — ALB IPs change, the hostname does not.
>
> The ACM validation CNAME (`_2a3ec5a...`) **must be grey cloud (DNS only)**. If proxied, AWS cannot reach it to auto-renew your SSL certificate.

### 12c. Change nameservers at Namecheap

Only do this after 12b is complete and all records are verified.

1. Log in to **namecheap.com → Domain List → Manage → buffden.com**
2. Under **Nameservers → Custom DNS**
3. Replace all 4 `awsdns` nameservers with the 2 Cloudflare nameservers shown in your dashboard
4. Save — propagation takes 5–30 minutes

**Rollback:** restore the 4 Route 53 nameservers at Namecheap at any time. The Route 53 hosted zone is never deleted.

### 12d. Configure SSL and security settings

**SSL/TLS → Overview:** set mode to **Full**

> Do not use Flexible — CloudFront enforces HTTPS and Flexible causes an infinite redirect loop.

**SSL/TLS → Edge Certificates:**

- Always Use HTTPS → **On**
- Minimum TLS Version → **TLS 1.2**

**Security → Settings:**

- Security Level → **Medium**
- Browser Integrity Check → **On**

### 12e. Add rate limit rule

**Security → WAF → Rate limiting rules → Create rule:**

```text
Rule name:      Protect URL creation endpoint
Field:          URI Path   equals   /api/urls
Characteristics: IP address
Rate:           20 requests per 10 seconds
Action:         Block
Duration:       10 seconds (free plan maximum)
```

> Free plan allows 1 rate limiting rule. The SPA (`tinyurl.buffden.com`) does not need one — it is served from CloudFront edge cache and flood traffic never reaches S3 or EC2.

### 12f. Verify Cloudflare is active

```bash
# Nameservers should show Cloudflare
nslookup -type=NS buffden.com

# cf-ray header confirms traffic flows through Cloudflare
curl -I https://go.buffden.com/actuator/health
# Look for: cf-ray: xxxxxxxx-XXX
```

### Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Redirect loop on `tinyurl.buffden.com` | SSL mode set to Flexible | Change SSL mode to Full |
| ACM certificate fails to renew | ACM validation CNAME is proxied | Set `_2a3ec5a...` record to grey cloud |
| `cf-ray` header missing | Propagation pending or record is grey cloud | Wait 30 min; verify orange cloud on `go` and `tinyurl` records |
| Site unreachable after nameserver switch | Record missing in Cloudflare | Restore Route 53 nameservers at Namecheap; add missing record; switch again |

### Under Attack Mode

If you detect an active flood: **Cloudflare dashboard → your domain → Quick Actions → Enable Under Attack Mode**. Disables automatically when toggled off.

### Optional cleanup

Once Cloudflare has been running without issues for 24 hours, delete the Route 53 hosted zone to stop the $0.50/month fee. Cloudflare is now the authoritative DNS.

---

## Verification

After all steps above, verify the network path is working:

```bash
# Should return HTTP 502 (ALB up, no app running yet — expected)
curl -I https://go.buffden.com

# Should return HTTP 301 redirect to https://
curl -I http://go.buffden.com

# Should return HTTP 301 or 403 (CloudFront up, no files in S3 yet — expected)
curl -I https://tinyurl.buffden.com
```

If you get 502 from `go.buffden.com` and a response from `tinyurl.buffden.com`, Phase A is complete.

**Proceed to [Phase B](PHASE_B_SECRETS_AND_CONFIG.md).**
