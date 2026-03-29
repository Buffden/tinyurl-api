# Troubleshooting — SSM Session Manager

**Symptom:** Clicking "Start session" in AWS Console does nothing, or throws a 400 error.
**Environment:** EC2 Ubuntu 22.04, public subnet, SSM-only access (no SSH key pair), IAM role `role-tinyurl-ec2` with `AmazonSSMManagedInstanceCore`.

---

## How EC2 Access Works in This Project

There is no SSH key pair on the EC2 instance. All access is via **AWS Systems Manager Session Manager** only. Port 22 is intentionally closed in the `tinyurl-ec2` security group.

```
Your terminal / browser
        │
        ▼
AWS SSM Service (HTTPS port 443)
        │
        ▼
SSM Agent on EC2 (receives commands over HTTPS — no open ports needed)
```

---

## Checklist Before Debugging

Before assuming something is broken, verify:

1. **Correct region** — top-right of AWS Console must show `us-east-1`. Session Manager is region-scoped.
2. **IAM role attached** — EC2 → Instances → `tinyurl-prod` → Security tab → IAM Role must show `role-tinyurl-ec2`.
3. **Correct subnet** — EC2 → Instances → `tinyurl-prod` → Networking tab → Subnet must be `10.0.1.0/24` (public). Private subnets (`10.0.3.x`, `10.0.4.x`) cannot reach SSM endpoints without VPC endpoints.

---

## Issue 1 — Clicking "Start session" Does Nothing

**Cause:** Browser popup blocker. Session Manager opens in a new tab — if popups are blocked for `console.aws.amazon.com`, clicking Start session does nothing visually with no error shown.

**Fix:**
- Chrome: click the blocked popup icon in the address bar → Allow popups from `console.aws.amazon.com`
- Safari: Settings → Websites → Pop-up Windows → Allow for `console.aws.amazon.com`
- Firefox: click the notification bar → Allow popups

**If it still does nothing after allowing popups:** try a different browser. Chrome has been observed failing silently with a `400 Bad Request` on `https://freetier.us-east-1.api.aws/` even with popups allowed. **Firefox resolved this instantly** with no other changes needed.

---

## Issue 2 — 400 Bad Request on `freetier.us-east-1.api.aws`

**Symptom:** Browser DevTools shows:
```
POST https://freetier.us-east-1.api.aws/
Status: 400 Bad Request
```

**Cause:** The AWS console browser client fails to establish the WebSocket tunnel. This is a browser-side issue, not an EC2 or IAM issue.

**Fixes to try in order:**

1. **Save Session Manager preferences** (must be done at least once per account):
   - Systems Manager → Session Manager → Preferences → Edit → scroll to bottom → Save
   - Even saving with all defaults is enough — the preferences just need to exist

2. **Switch browser** — Firefox resolves this when Chrome fails. The WebSocket implementation differs between browsers.

3. **Use AWS CLI instead of browser** (permanent fix — see Issue 5 below)

---

## Issue 3 — Instance Not Appearing in Fleet Manager

**Symptom:** Systems Manager → Fleet Manager shows no instances, or the instance disappears.

**Cause:** SSM Agent process crashed or lost connection to AWS SSM service. The instance may still appear in Session Manager's target list (cached) but cannot accept sessions.

**Diagnosis:**
- Go to **Systems Manager → Run Command → Run command**
- Document: `AWS-RunShellScript`, Command: `systemctl status amazon-ssm-agent`
- Target: your instance
- If this stays "In Progress" for more than 2 minutes → SSM Agent is unresponsive

**Fix — Stop and Start EC2 instance:**

> Use **Stop then Start**, NOT Reboot. Stop+Start moves the instance to fresh hardware and fully reinitialises all services. Reboot keeps the same hardware and may not recover a crashed agent.

1. EC2 → Instances → `tinyurl-prod` → Instance state → **Stop**
2. Wait for status: **Stopped** (~30 seconds)
3. Instance state → **Start**
4. Wait for **2/2 status checks passed** (~3 minutes)
5. Wait an additional **3–5 minutes** for SSM Agent to register with Fleet Manager
6. Check Fleet Manager — instance should appear as **Online**

> After stop+start, Docker containers restart automatically because they have `restart: unless-stopped` in `docker-compose.prod.yml`.

> After stop+start, the EC2 **public IP changes**. This does not affect SSM Session Manager but update any direct IP references if needed. The private IP stays the same.

---

## Issue 4 — EC2 Instance Connect Fails After Opening Port 22

**Symptom:** Added port 22 inbound rule to `tinyurl-ec2` security group with "My IP" but EC2 Instance Connect still fails.

**Cause:** EC2 Instance Connect browser-based connect does NOT come from your IP. The connection to port 22 is made by **AWS's EC2 Instance Connect service**, not your browser directly. The source IP must be the AWS service range.

**Fix — Use the correct source IP range:**

The security group inbound rule must be:

| Type | Port | Source |
|---|---|---|
| SSH | 22 | `18.206.107.24/29` |

This IP range is **officially AWS-owned** — verified from AWS's own published IP ranges:
```bash
curl -s https://ip-ranges.amazonaws.com/ip-ranges.json | python3 -c "
import json, sys
data = json.load(sys.stdin)
results = [p for p in data['prefixes']
           if p.get('service') == 'EC2_INSTANCE_CONNECT'
           and p.get('region') == 'us-east-1']
for r in results: print(r)
"
# Output: {'ip_prefix': '18.206.107.24/29', 'region': 'us-east-1', 'service': 'EC2_INSTANCE_CONNECT', ...}
```

**After connecting, remove port 22 immediately:**
- EC2 → Security Groups → `tinyurl-ec2` → Inbound rules → Edit → delete SSH rule → Save

> EC2 Instance Connect is a temporary emergency access method only. SSM Session Manager is the correct permanent access method for this project.

---

## Issue 5 — Permanent Fix: AWS CLI Session Manager

Bypasses the browser WebSocket entirely. Use this as the primary access method.

**Install Session Manager plugin on Mac:**

```bash
# Apple Silicon (M1/M2/M3)
curl "https://s3.amazonaws.com/session-manager-downloads/plugin/latest/mac_arm64/sessionmanager-bundle.zip" \
  -o "sessionmanager-bundle.zip"
unzip sessionmanager-bundle.zip
sudo ./sessionmanager-bundle/install \
  -i /usr/local/sessionmanagerplugin \
  -b /usr/local/bin/session-manager-plugin

# Intel Mac
curl "https://s3.amazonaws.com/session-manager-downloads/plugin/latest/mac/sessionmanager-bundle.zip" \
  -o "sessionmanager-bundle.zip"
unzip sessionmanager-bundle.zip
sudo ./sessionmanager-bundle/install \
  -i /usr/local/sessionmanagerplugin \
  -b /usr/local/bin/session-manager-plugin
```

**Connect to EC2:**

```bash
aws ssm start-session --target <your-ec2-instance-id> --region us-east-1
```

Once connected, switch to the ubuntu user:

```bash
sudo su - ubuntu
```

---

## Verifying SSM Agent Health From Inside the Instance

If you get into the instance via EC2 Instance Connect, run these to diagnose SSM:

```bash
# Check agent status
sudo systemctl status snap.amazon-ssm-agent.amazon-ssm-agent.service

# Check last 50 log lines
sudo journalctl -u snap.amazon-ssm-agent.amazon-ssm-agent.service --no-pager -n 50

# Restart agent if needed
sudo systemctl restart snap.amazon-ssm-agent.amazon-ssm-agent.service

# Confirm agent is enabled on boot
sudo systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service
```

**Healthy agent output looks like:**
```
Active: active (running)
INFO EC2RoleProvider Successfully connected with instance profile role credentials
INFO [CredentialRefresher] Credentials ready
INFO [CredentialRefresher] Next credential rotation will be in 29.9... minutes
```

**The `/etc/amazon/ssm/seelog.xml: no such file or directory` warning is harmless** — the agent falls back to default logging config and works normally.

---

## Quick Reference — Access Methods

| Method | When to use | Requires |
|---|---|---|
| SSM Session Manager (Firefox) | Normal day-to-day access | Nothing extra |
| AWS CLI `ssm start-session` | When browser fails | Session Manager plugin installed |
| EC2 Instance Connect | Emergency only — SSM agent dead | Port 22 open from `18.206.107.24/29` |

---

## What NOT to Do

- **Do not leave port 22 open** after finishing EC2 Instance Connect — remove the rule immediately
- **Do not use "My IP" as the source** for EC2 Instance Connect — use `18.206.107.24/29`
- **Do not use Reboot** to recover a crashed SSM agent — use Stop then Start
- **Do not panic if the instance disappears from Fleet Manager** — Stop+Start always recovers it
