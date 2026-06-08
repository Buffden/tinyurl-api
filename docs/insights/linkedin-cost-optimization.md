My AWS bill was forecasting $81/month across multiple projects. I got it to $52 without removing a single feature.

EC2, RDS, ALB, CloudFront, GitHub Actions CI/CD, multi-layer security. All of it is running to production standards. I audited the entire infrastructure and brought the bill down 36%. But the more interesting story is where the real savings came from.

One change was the biggest single contributor.

A Lambda function triggered by EventBridge shuts down EC2 and RDS every night. Weekends are fully off, back on weekdays at 8 AM. 93 hours of zero EC2/RDS runtime cost per week. Behind it: an SQS DLQ for failed invocations, a CloudWatch Alarm wired to SNS. Because a missed stop that goes unnoticed is just a delayed line item on the next bill, that single change accounted for ~22% of the total bill reduction.

CloudWatch logs showed the EC2 averaging below 1% CPU. Downsized from t3.small to t3.micro after validating no performance regression. An idle EIP and an orphaned VPC were audited, confirmed unreferenced, and removed.

The SPA on S3 and CloudFront instead of a VM. Four backend services on a single EC2 via Docker Compose instead of ECS/Fargate. No bastion host, no NAT Gateway, no ECR. Secrets in SSM Parameter Store Standard tier instead of Secrets Manager. CI/CD over OIDC.

The rate-limiting architecture is a cost decision too, not just a security layer.

Cloudflare absorbs abuse and bot traffic before it reaches AWS metered infrastructure. Every request stopped at the edge never triggers EC2, RDS, or data transfer charges. A public-facing URL shortener is a real target. Nginx takes it further. Known scanners get a 444, connection closed at the TCP level before a single byte is sent back.

Two more changes are on the table: removing the ALB and migrating RDS to Dockerized PostgreSQL. Both save real money; both have trade-offs I'm still working through. If both land, the total reduction hits 64%.

The most expensive decisions aren't the ones that show up on the bill. They're the ones that shaped the architecture before the bill existed.

Next: the security architecture, Cloudflare over AWS WAF, and the four-layer rate limiting design.

Production Link: https://tinyurl.buffden.com/

Source code: https://github.com/Buffden/tinyurl-api

Previous post: https://www.linkedin.com/feed/update/urn:li:activity:7447693051248046080/

#AWS #CloudCost #SystemDesign #DevOps #SpringBoot #PostgreSQL #Cloudflare