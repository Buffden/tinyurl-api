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

### sg-alb (Internet-facing load balancer)

1. **VPC → Security Groups → Create security group**
2. Name: `sg-tinyurl-alb`, VPC: `tinyurl-prod-vpc`
3. Inbound rules:
   - HTTP (80) from `0.0.0.0/0`
   - HTTPS (443) from `0.0.0.0/0`
4. Outbound: All traffic (default)

### sg-ec2 (Application server)

1. Name: `sg-tinyurl-ec2`, VPC: `tinyurl-prod-vpc`
2. Inbound rules:
   - HTTP (80) from source: `sg-tinyurl-alb` (not `0.0.0.0/0` — only ALB can reach EC2)
3. Outbound: All traffic (default)

> No port 22. SSH is not used. EC2 access is via SSM Session Manager only.

### sg-rds (Database)

1. Name: `sg-tinyurl-rds`, VPC: `tinyurl-prod-vpc`
2. Inbound rules:
   - PostgreSQL (5432) from source: `sg-tinyurl-ec2`
3. Outbound: None (remove default rule)

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
            "repo:buffden/tinyurl:ref:refs/heads/main",
            "repo:buffden/tinyurl-gui:ref:refs/heads/main"
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
> The `StringLike` condition means only the `main` branch of your two repos can assume this role.

---

## Step 6 — RDS PostgreSQL

### 6a. Create DB subnet group

1. Go to **RDS → Subnet groups → Create DB subnet group**
2. Name: `tinyurl-rds-subnet-group`
3. VPC: `tinyurl-prod-vpc`
4. Add subnets: both private subnets (`10.0.3.0/24` and `10.0.4.0/24`)

### 6b. Create RDS instance

1. Go to **RDS → Databases → Create database**
2. Engine: **PostgreSQL**, version: **16.x** (latest 16)
3. Template: **Free tier** (selects db.t3.micro automatically — change to db.t3.micro if not)
4. DB instance identifier: `tinyurl-prod`
5. Master username: `tinyurl`
6. Master password: generate a strong random password — **save this, you will put it in SSM**
7. Instance class: `db.t3.micro`
8. Storage: 5 GB gp3, enable auto-scaling (max 20 GB)
9. VPC: `tinyurl-prod-vpc`
10. DB subnet group: `tinyurl-rds-subnet-group`
11. Public access: **No**
12. VPC security group: `sg-tinyurl-rds`
13. Initial database name: `tinyurl`
14. Automated backups: Enabled, 7-day retention
15. Encryption: Enabled (AWS managed key)
16. Deletion protection: **Enabled**
17. Click **Create database** — takes ~5 minutes

> After creation, copy the **Endpoint** (e.g. `tinyurl-prod.xyz.us-east-1.rds.amazonaws.com`). You will need it for SSM Parameter Store in Phase B.

---

## Step 7 — EC2 Instance

1. Go to **EC2 → Launch instance**
2. Name: `tinyurl-prod`
3. AMI: **Ubuntu Server 22.04 LTS** (64-bit x86)
4. Instance type: `t3.small`
5. Key pair: **Proceed without a key pair** (access is via SSM — no SSH needed)
6. Network settings:
   - VPC: `tinyurl-prod-vpc`
   - Subnet: `tinyurl-public-1a` (us-east-1a)
   - Auto-assign public IP: **Enable**
   - Security group: `sg-tinyurl-ec2`
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
7. Security groups: `sg-tinyurl-alb`
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
4. Block all public access: **On** (all four checkboxes)
5. Versioning: Disabled
6. Encryption: SSE-S3 (default)
7. Click **Create bucket**

> Do not enable static website hosting — CloudFront handles routing.

---

## Step 10 — CloudFront Distribution

1. Go to **CloudFront → Create distribution**
2. Origin domain: select `tinyurl-spa-prod.s3.us-east-1.amazonaws.com`
3. Origin access: **Origin access control settings (recommended)**
   - Click **Create new OAC**, name: `tinyurl-spa-oac`, sign requests: Yes
   - After creation, copy the S3 bucket policy that CloudFront shows — you will apply it in the next step
4. Default cache behavior:
   - Viewer protocol policy: **Redirect HTTP to HTTPS**
   - Cache policy: `CachingOptimized`
5. Settings:
   - Price class: **Use only North America and Europe**
   - Alternate domain names (CNAMEs): `tinyurl.buffden.com`
   - Custom SSL certificate: select `*.buffden.com`
   - Default root object: `index.html`
6. Click **Create distribution**

**Apply S3 bucket policy (OAC):**

1. Go to **S3 → tinyurl-spa-prod → Permissions → Bucket policy**
2. Paste the policy CloudFront generated in step 3 above
3. Save

**Add custom error pages (required for Angular routing):**

1. CloudFront → your distribution → **Error pages → Create custom error response**
2. HTTP error code: **403** → Response page path: `/index.html` → HTTP response code: **200**
3. Repeat for HTTP error code: **404**

> These error pages are critical. Without them, refreshing any Angular route (e.g. `/dashboard`) will return a 403/404 from S3 instead of serving the SPA.

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
