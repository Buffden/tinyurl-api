# Phase D — CI/CD Automation

**Goal:** Merge to `main` on either repo automatically deploys to production. No manual steps after this.
**Prerequisites:** Phase C complete (first manual deploy working)
**Estimated time:** 2–3 hours

---

## Checklist

- [ ] Step 1 — Add GitHub Secrets to both repos
- [ ] Step 2 — Create backend deploy workflow
- [ ] Step 3 — Create frontend deploy workflow
- [ ] Step 4 — Update existing CI workflow to gate deploys
- [ ] Step 5 — Test by merging a small change to `main`

---

## How Deployment Works (No SSH)

Instead of SSH, GitHub Actions uses **AWS SSM RunCommand** to execute commands on EC2:

```
GitHub Actions
    │
    ├── Authenticates to AWS via OIDC (no keys stored)
    │
    ├── Pushes Docker image to GHCR
    │
    └── Calls AWS SSM RunCommand API
            │
            └── SSM Agent on EC2 receives command
                    │
                    └── Runs docker compose pull + up -d
```

The EC2 instance never needs a public SSH port open. SSM agent (already installed via `AmazonSSMManagedInstanceCore` policy) receives commands over HTTPS through the SSM service.

---

## Step 1 — Add GitHub Secrets

Only one secret is stored in GitHub — everything else lives in AWS Parameter Store.

### Backend repo (`buffden/tinyurl-api`)

**GitHub → repository → Settings → Secrets and variables → Actions → New repository secret:**

| Secret name | Value |
|---|---|
| `AWS_ROLE_ARN` | `arn:aws:iam::<account-id>:role/role-github-actions-tinyurl` |

### Frontend repo (`buffden/tinyurl-gui`)

| Secret name | Value |
|---|---|
| `AWS_ROLE_ARN` | `arn:aws:iam::<account-id>:role/role-github-actions-tinyurl` |

> **Important:** `AWS_ROLE_ARN` must be the **IAM role ARN** — not the OIDC provider ARN.
> - ✅ Correct: `arn:aws:iam::911167927589:role/role-github-actions-tinyurl`
> - ❌ Wrong:   `arn:aws:iam::911167927589:oidc-provider/token.actions.githubusercontent.com`

### AWS Parameter Store entries (fetched at runtime by the workflows)

| Parameter path | Used by |
|---|---|
| `/tinyurl/cicd/ec2-instance-id` | Backend deploy — SSM RunCommand target |
| `/tinyurl/cicd/rds-endpoint` | Backend deploy — passed to EC2 on deploy |
| `/tinyurl/cicd/cf-dist-id` | Frontend deploy — CloudFront invalidation |

> Find EC2 instance ID: AWS Console → EC2 → Instances → copy Instance ID
> Find RDS endpoint: AWS Console → RDS → Databases → `tinyurl-prod` → Connectivity → Endpoint
> Find CloudFront distribution ID: AWS Console → CloudFront → Distributions → copy ID column

---

## Step 1b — GitHub Repository Settings

### Backend repo only

**GitHub → buffden/tinyurl-api → Settings → Actions → General → Workflow permissions**

Set to: **Read and write permissions** → Save.

This is required for the `GITHUB_TOKEN` to push Docker images to GHCR (`ghcr.io/buffden/tinyurl-api`).
The frontend repo does not need this — it only pushes to S3 via the IAM role.

---

## Step 2 — Backend Deploy Workflow

Create `.github/workflows/deploy.yml` in the **backend repo** (`buffden/tinyurl-api`):

```yaml
name: Deploy API

on:
  push:
    branches: [main]

jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Run tests
        run: ./gradlew clean test

      - name: Build JAR
        run: ./gradlew bootJar

  compose-smoke:
    needs: build-test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Start stack
        run: docker compose up -d --build

      - name: Wait for health
        run: |
          for i in {1..24}; do
            curl -sf http://localhost:8080/actuator/health && echo "Healthy" && exit 0
            echo "Waiting... ($i/24)"
            sleep 5
          done
          echo "Health check timed out"
          docker compose logs
          exit 1

      - name: Tear down
        if: always()
        run: docker compose down -v

  deploy:
    needs: [build-test, compose-smoke]
    runs-on: ubuntu-latest
    permissions:
      id-token: write   # Required for OIDC
      contents: read
      packages: write   # Required for GHCR push

    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS credentials (OIDC)
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: us-east-1

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push Docker image
        run: |
          docker build -t ghcr.io/buffden/tinyurl-api:${{ github.sha }} .
          docker push ghcr.io/buffden/tinyurl-api:${{ github.sha }}

      - name: Deploy via SSM RunCommand
        run: |
          COMMAND_ID=$(aws ssm send-command \
            --instance-ids "${{ secrets.EC2_INSTANCE_ID }}" \
            --document-name "AWS-RunShellScript" \
            --parameters '{"commands":[
              "export IMAGE_TAG=${{ github.sha }}",
              "export RDS_ENDPOINT=${{ secrets.RDS_ENDPOINT }}",
              "cd /app",
              "docker compose -f docker-compose.prod.yml pull",
              "docker compose -f docker-compose.prod.yml up -d",
              "sleep 20",
              "curl -sf http://localhost/actuator/health || exit 1"
            ]}' \
            --output text \
            --query "Command.CommandId")

          echo "SSM Command ID: $COMMAND_ID"

          # Wait for command to complete (max 3 minutes)
          for i in {1..18}; do
            STATUS=$(aws ssm get-command-invocation \
              --command-id "$COMMAND_ID" \
              --instance-id "${{ secrets.EC2_INSTANCE_ID }}" \
              --query "Status" \
              --output text)
            echo "Status: $STATUS ($i/18)"
            if [ "$STATUS" = "Success" ]; then
              echo "Deploy succeeded"
              exit 0
            elif [ "$STATUS" = "Failed" ] || [ "$STATUS" = "TimedOut" ] || [ "$STATUS" = "Cancelled" ]; then
              echo "Deploy failed with status: $STATUS"
              aws ssm get-command-invocation \
                --command-id "$COMMAND_ID" \
                --instance-id "${{ secrets.EC2_INSTANCE_ID }}" \
                --query "StandardErrorContent" \
                --output text
              exit 1
            fi
            sleep 10
          done
          echo "Deploy timed out waiting for SSM"
          exit 1
```

---

## Step 3 — Frontend Deploy Workflow

Create `.github/workflows/deploy.yml` in the **frontend repo** (`buffden/tinyurl-gui`):

```yaml
name: Deploy Frontend

on:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Build Angular (production)
        run: npx ng build --configuration=production

      - name: Upload build artifact
        uses: actions/upload-artifact@v4
        with:
          name: angular-dist
          path: dist/browser/
          retention-days: 1

  deploy:
    needs: build
    runs-on: ubuntu-latest
    permissions:
      id-token: write
      contents: read

    steps:
      - name: Download build artifact
        uses: actions/download-artifact@v4
        with:
          name: angular-dist
          path: dist/browser/

      - name: Configure AWS credentials (OIDC)
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: us-east-1

      - name: Upload to S3
        run: |
          aws s3 sync dist/browser/ s3://tinyurl-spa-prod/ \
            --delete \
            --cache-control "public, max-age=31536000, immutable" \
            --exclude "index.html"

          # index.html must not be cached — always serve latest
          aws s3 cp dist/browser/index.html s3://tinyurl-spa-prod/index.html \
            --cache-control "no-cache, no-store, must-revalidate"

      - name: Invalidate CloudFront cache
        run: |
          INVALIDATION_ID=$(aws cloudfront create-invalidation \
            --distribution-id ${{ secrets.CF_DIST_ID }} \
            --paths "/*" \
            --query "Invalidation.Id" \
            --output text)

          echo "Invalidation ID: $INVALIDATION_ID"

          # Wait for invalidation to complete
          aws cloudfront wait invalidation-completed \
            --distribution-id ${{ secrets.CF_DIST_ID }} \
            --id "$INVALIDATION_ID"

          echo "CloudFront cache cleared"
```

> The S3 upload uses two different cache-control settings:
> - Hashed JS/CSS files (`main.abc123.js`) → `max-age=31536000` (1 year) — safe because filename changes on each build
> - `index.html` → `no-cache` — must always be fresh so users get the latest app version

---

## Step 4 — Update Existing CI Workflow

The existing `ci-workflows.yml` runs on PRs. Add a condition so the deploy job only runs on `main`, not on PRs. If you are creating a new `deploy.yml` as above (separate from CI), no change is needed — the existing CI workflow continues to run tests on PRs.

Verify the PR workflow in `.github/workflows/ci-workflows.yml` does **not** have a deploy step (it shouldn't push images or deploy). Tests only on PRs, deploy only on merge to `main`.

---

## Step 5 — Test the Pipeline

Make a small, safe change and merge it:

```bash
# Backend: add a comment to a file
# Commit, push to a branch, open PR, merge to main
# Watch GitHub Actions → the deploy job should run automatically
```

Verify:
1. GitHub Actions tab → `Deploy API` workflow runs
2. All three jobs complete: `build-test` → `compose-smoke` → `deploy`
3. ALB health check stays green during deploy
4. `curl https://go.buffden.com/actuator/health` still returns 200 after deploy

---

## Full Pipeline Summary

```
Backend repo — PR opened
  ├── build-test     ./gradlew clean test
  └── compose-smoke  docker compose up + health check
      └── [all pass] → PR can be merged

Backend repo — merged to main
  ├── build-test
  ├── compose-smoke
  └── deploy
      ├── OIDC → assume role-github-actions-tinyurl
      ├── docker build + push to ghcr.io/buffden/tinyurl-api:<sha>
      └── SSM RunCommand → EC2 pulls + restarts containers

Frontend repo — merged to main
  ├── build          ng build --configuration=production
  └── deploy
      ├── OIDC → assume role-github-actions-tinyurl
      ├── aws s3 sync → tinyurl-spa-prod
      └── cloudfront create-invalidation → wait for completion
```

---

---

## Gotchas — Issues Hit During Setup

### 1. `AWS_ROLE_ARN` secret must be the IAM role ARN, not the OIDC provider ARN

The OIDC provider ARN and the role ARN look similar — easy to copy the wrong one.

- ❌ OIDC provider: `arn:aws:iam::911167927589:oidc-provider/token.actions.githubusercontent.com`
- ✅ IAM role: `arn:aws:iam::911167927589:role/role-github-actions-tinyurl`

---

### 2. IAM trust policy `sub` condition is case-sensitive

`StringLike` in IAM is case-sensitive. The GitHub org name in the OIDC token must match exactly.
Check your GitHub org/username case and use it exactly in the trust policy:

```json
"repo:Buffden/tinyurl-api:*",
"repo:Buffden/tinyurl-gui:*"
```

If you use lowercase when your org is capitalised (or vice versa), OIDC will silently fail with `Not authorized to perform sts:AssumeRoleWithWebIdentity`.

---

### 3. Trust policy must use `*` wildcard, not `ref:refs/heads/main`

Using `ref:refs/heads/main` blocks `workflow_dispatch` runs from any other branch.
Use `*` to allow all branches and trigger types:

```json
"repo:Buffden/tinyurl-api:*"
```

---

### 4. Backend repo requires "Read and write permissions" for GHCR

**GitHub → buffden/tinyurl-api → Settings → Actions → General → Workflow permissions → Read and write permissions**

Without this, the `GITHUB_TOKEN` cannot push to `ghcr.io/buffden/tinyurl-api` even with `packages: write` in the job permissions. The frontend repo does not need this setting.

---

### 7. GHCR package must be linked to the repository

If the container image was ever pushed manually (e.g. with a PAT or local `docker push`), the resulting GHCR package is not automatically linked to the repository. The `GITHUB_TOKEN` can only push to packages that are explicitly connected to the repo it runs from.

**Symptom:** Build succeeds, layers start uploading, then `denied: permission_denied: write_package`.

**Fix:**

1. Go to **GitHub → buffden → Packages → tinyurl-api → Package settings**
2. Scroll to **"Manage Actions access"**
3. Click **"Add Repository"** → select `buffden/tinyurl-api` → set role to **Write**

This only needs to be done once. After linking, all future workflow runs can push without issue.

---

### 5. Angular build output path is `dist/tinyurl-gui/browser/`, not `dist/browser/`

Angular outputs to `dist/<project-name>/browser/`. The project name is defined in `angular.json`.
For this project: `dist/tinyurl-gui/browser/`. Using the wrong path causes the artifact upload to silently succeed with zero files, and the download step fails with `Artifact not found`.

---

### 6. CloudFront distribution ID in Parameter Store must be the real ID

`/tinyurl/cicd/cf-dist-id` must contain the actual distribution ID (e.g. `E2JO8EQWSPGUL5`), not a placeholder.
Find it: **AWS Console → CloudFront → Distributions → ID column**.

---

**Proceed to [Phase E](PHASE_E_OBSERVABILITY.md).**
