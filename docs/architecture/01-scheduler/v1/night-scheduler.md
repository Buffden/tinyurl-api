# Night Scheduler — v1

> Stop EC2 and RDS every night. Start them back on weekdays. Zero idle compute cost overnight and all weekend.

---

## Overview

Both projects are portfolio and demo workloads. No real users visit at 2 AM on a Sunday. Running full production infrastructure around the clock for that audience is waste, not reliability.

A Lambda function triggered by EventBridge Scheduler stops all EC2 instances and RDS every night, and starts them back on weekday mornings. Weekends stay fully off.

**Result:** 60 hours of zero compute cost per week. ~22% reduction on the monthly AWS bill. The scheduler itself costs nothing — all five supporting services stay within the AWS free tier at this invocation rate.

---

## Schedule

| Event | Time | Days |
| --- | --- | --- |
| Stop all | 11:00 PM | Every day |
| Start all | 8:00 AM | Monday–Friday only |

Weekend behavior: instances stop Friday at 11 PM and do not start again until Monday at 8 AM.

---

## Architecture

See `scheduler-diagram.excalidraw` in this folder for the visual.

```
IAM Role                                              IAM Role
(EventBridge →                               (Lambda execution:
 invoke Lambda only)                          EC2 + RDS stop/start only)
      │                                                │
      ▼                                                │
EventBridge Scheduler       EventBridge Scheduler      │
  (Stop — nightly)            (Start — weekdays)       │
          │                          │                 │
          └──────────────┬───────────┘                 │
                         ▼                             │
                  Lambda Function  ◄───────────────────┘
                  (Stop / start logic)
                         │
           ┌─────────────┼─────────────┐
           ▼             ▼             ▼
        EC2 (EMS)   EC2 (TinyURL)   RDS (TinyURL)

                          │
                    On any result
               ┌──────────┴──────────┐
               ▼                     ▼
       CloudWatch Logs         On Lambda error (two independent paths)
       (every invocation)        │                        │
                          failed event             Lambda error metric
                          payload                         │
                               │                  CloudWatch Alarm
                        SQS Dead Letter                   │
                        Queue (replay)               SNS Topic
                                                         │
                                                    Email Alert
```

---

## Why Each Component Exists

### EventBridge Scheduler

The scheduling layer. Fires the Lambda on a cron expression with timezone awareness — no UTC offset arithmetic, no drift. Two separate schedules: one for stop, one for start. Each passes an action payload to Lambda so the same function handles both paths.

Used over the older EventBridge Rules because Scheduler has a persistent schedule store, supports named groups, and is the current AWS standard for this pattern.

### Lambda

Runs the stop/start logic. No servers, no idle cost. Receives the action from EventBridge, calls the EC2 and RDS APIs, logs the result, and exits. Execution takes a few seconds per invocation.

The function is stateless and has no side effects beyond stopping or starting the three resources it is scoped to.

### IAM Role

Least-privilege. The role attached to Lambda can stop and start only the specific EC2 instances and RDS instance it manages — nothing else in the account. A bug or misconfiguration in the function cannot affect any other resource.

A separate role is attached to EventBridge Scheduler, scoped to invoking only this Lambda function.

### SQS Dead Letter Queue

If Lambda throws an unhandled exception, the failed event payload is routed to the DLQ instead of disappearing. Without it, a failed stop invocation produces no record — the instances stay on, the bill accumulates, and there is nothing to investigate. The DLQ ensures every failure is captured and can be replayed once the root cause is fixed.

### CloudWatch Logs

Every invocation writes structured logs. Full execution history — which instances were targeted, what state transitions occurred, any warnings — without needing SSH access to anything.

### CloudWatch Alarm + SNS

The alarm watches the Lambda error metric. If Lambda errors, the alarm fires and publishes to an SNS topic which sends an email alert.

This matters because a missed stop that goes unnoticed is just a delayed line item on the next bill. The alert closes that gap — failure is visible immediately, not at the end of the month.

---

## Cost

All five services stay within the AWS free tier at this invocation rate. The scheduler itself adds nothing to the bill while saving ~$18/month in EC2 and RDS compute hours.

Note: the ALB charges 24/7 regardless of whether the EC2 is running. Scheduler savings apply only to EC2 and RDS instance hours.

---

## Design Decisions

**Why not GitHub Actions scheduled workflows?**
Simpler to implement, but depends on GitHub availability. If GitHub has an incident during the nightly stop window, instances stay on. An AWS-native scheduler is more reliable for a billing-sensitive job.

**Why Lambda over EventBridge direct SDK targets?**
EventBridge Scheduler can invoke EC2 and RDS APIs directly without Lambda. The Lambda approach was chosen because it adds observability (structured logs, DLQ, error metrics) that direct targets do not provide. For an unattended nightly job, knowing what happened matters more than removing one service from the stack.

**Why not always-on infrastructure?**
These are portfolio projects. The scheduler is a deliberate trade-off: lower availability in exchange for lower cost. Recruiters and interviewers are not visiting at 2 AM on a Sunday. If the service is needed outside the window, it can be started manually in under a minute.
