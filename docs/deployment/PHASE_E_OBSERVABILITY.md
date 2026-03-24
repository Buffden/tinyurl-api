# Phase E — Observability

**Goal:** Know when something is broken before users report it.
**Prerequisites:** Phase D complete (CI/CD running, app in production)
**Estimated time:** 1 hour

---

## Checklist

- [ ] Step 1 — Verify CloudWatch log groups receiving logs
- [ ] Step 2 — Create CloudWatch alarms
- [ ] Step 3 — Enable RDS Enhanced Monitoring
- [ ] Step 4 — Enable RDS Performance Insights

---

## Step 1 — Verify CloudWatch Logs

The `docker-compose.prod.yml` uses the `awslogs` driver to ship container logs to CloudWatch. After Phase C or D, logs should already be flowing.

Verify:
1. Go to **CloudWatch → Log groups**
2. You should see `/tinyurl/prod` with two log streams: `app` and `nginx`
3. Click into `app` → verify Spring Boot startup logs are visible

If log group is empty:
- Confirm EC2 IAM role has `CloudWatchLogsFullAccess` or this inline policy:

```json
{
  "Effect": "Allow",
  "Action": [
    "logs:CreateLogGroup",
    "logs:CreateLogStream",
    "logs:PutLogEvents",
    "logs:DescribeLogStreams"
  ],
  "Resource": "arn:aws:logs:us-east-1:*:log-group:/tinyurl/prod:*"
}
```

Add this to `role-tinyurl-ec2` if missing, then restart containers:
```bash
docker compose -f docker-compose.prod.yml down
docker compose -f docker-compose.prod.yml up -d
```

---

## Step 2 — CloudWatch Alarms

Create these alarms in **CloudWatch → Alarms → Create alarm**.

### Alarm 1 — EC2 High CPU

Triggers if the EC2 instance is under sustained load (memory pressure, infinite loop, etc.).

1. Metric: **EC2 → Per-Instance Metrics → CPUUtilization** → select `tinyurl-prod`
2. Statistic: Average, Period: 5 minutes
3. Condition: **Greater than 80%**
4. Consecutive periods: 2 (alerts after 10 minutes of high CPU)
5. Action: Create new SNS topic `tinyurl-alerts`, add your email
6. Alarm name: `tinyurl-ec2-high-cpu`

### Alarm 2 — RDS High Connections

db.t3.micro supports a maximum of ~87 connections. Alert before it's full.

1. Metric: **RDS → Per-Database Metrics → DatabaseConnections** → select `tinyurl-prod`
2. Statistic: Maximum, Period: 1 minute
3. Condition: **Greater than 75**
4. Action: Use SNS topic `tinyurl-alerts`
5. Alarm name: `tinyurl-rds-high-connections`

### Alarm 3 — ALB 5xx Errors

Triggers if Spring Boot is returning server errors at scale.

1. Metric: **ApplicationELB → Per AppELB Metrics → HTTPCode_Target_5XX_Count** → select `tinyurl-alb`
2. Statistic: Sum, Period: 1 minute
3. Condition: **Greater than 5**
4. Action: Use SNS topic `tinyurl-alerts`
5. Alarm name: `tinyurl-alb-5xx`

### Alarm 4 — ALB Slow Responses

Triggers if the application is responding slowly (DB bottleneck, connection pool exhaustion).

1. Metric: **ApplicationELB → Per AppELB Metrics → TargetResponseTime** → select `tinyurl-alb`
2. Statistic: p99 (99th percentile), Period: 5 minutes
3. Condition: **Greater than 1** (1 second)
4. Action: Use SNS topic `tinyurl-alerts`
5. Alarm name: `tinyurl-alb-slow-p99`

### Confirm SNS email subscription

After creating the first alarm with the SNS topic, AWS sends a confirmation email. Click **Confirm subscription** in that email — alarms won't send notifications until confirmed.

---

## Step 3 — RDS Enhanced Monitoring

Enhanced Monitoring gives 1-minute granularity OS-level metrics (CPU, memory, disk I/O).

1. Go to **RDS → Databases → tinyurl-prod → Modify**
2. Monitoring section → **Enhanced Monitoring: Enable**
3. Granularity: **1 minute**
4. Monitoring Role: **Default** (AWS creates it automatically)
5. Click **Continue → Apply immediately**

Metrics appear in **CloudWatch → Log groups → RDSOSMetrics**.

---

## Step 4 — RDS Performance Insights

Performance Insights shows which SQL queries are causing load — useful if the app slows down.

1. Go to **RDS → Databases → tinyurl-prod → Modify**
2. Performance Insights section → **Enable Performance Insights**
3. Retention period: **7 days** (free tier)
4. Master key: Default (AWS managed)
5. Click **Continue → Apply immediately**

Access via **RDS → Databases → tinyurl-prod → Performance Insights tab**.

---

## What to Monitor in v1

| Metric | Normal range | Alert threshold | What it means if breached |
|---|---|---|---|
| EC2 CPU | <30% | >80% sustained | App under load or runaway process |
| RDS connections | <20 | >75 | HikariCP pool exhausted |
| ALB 5xx count | 0 | >5/min | Spring Boot throwing unhandled exceptions |
| ALB P99 latency | <100ms | >1s | DB slow query or connection pool wait |
| Redirect success rate | 100% | — | Monitor manually via logs for now |

---

**Proceed to [Phase F](PHASE_F_HARDENING.md).**
