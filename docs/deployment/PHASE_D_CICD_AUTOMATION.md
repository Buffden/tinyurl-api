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

### Backend repo (`buffden/tinyurl`)

Go to GitHub → repository → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret name | Value |
|---|---|
| `EC2_INSTANCE_ID` | Your EC2 instance ID (e.g. `i-0abc1234def56789`) |
| `RDS_ENDPOINT` | RDS hostname (e.g. `tinyurl-prod.xyz.us-east-1.rds.amazonaws.com`) |
| `AWS_ROLE_ARN` | `arn:aws:iam::<account-id>:role/role-github-actions-tinyurl` |

> Find EC2 instance ID: AWS Console → EC2 → Instances → click your instance → copy Instance ID
> Find RDS endpoint: AWS Console → RDS → Databases → `tinyurl-prod` → Connectivity → Endpoint

### Frontend repo (`buffden/tinyurl-gui`)

| Secret name | Value |
|---|---|
| `CF_DIST_ID` | CloudFront distribution ID (e.g. `E1ABC2DEF3GH4IJ`) |
| `AWS_ROLE_ARN` | `arn:aws:iam::<account-id>:role/role-github-actions-tinyurl` |

> Find CloudFront distribution ID: AWS Console → CloudFront → Distributions → copy ID column

---

## Step 2 — Backend Deploy Workflow

Create `.github/workflows/deploy.yml` in the **backend repo** (`buffden/tinyurl`):

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

**Proceed to [Phase E](PHASE_E_OBSERVABILITY.md).**
