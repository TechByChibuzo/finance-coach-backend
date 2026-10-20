# Finance Coach infrastructure (Terraform)

ALB + ECS Fargate + RDS Postgres in one region. See `docs/SYSTEM_DESIGN.md` for the architecture.

Roughly $50-70/month: load balancer ~$16, Fargate (0.5 vCPU / 1 GB) ~$18, RDS db.t4g.micro ~$13-15,
public IPv4 addresses, logs and Secrets Manager. No NAT gateway.

## One-time setup

1. Install Terraform: `brew install hashicorp/tap/terraform`
2. Give the AWS CLI credentials for the account (an admin IAM user or IAM Identity Center),
   e.g. `aws configure --profile financecoach` (region us-east-2), then `export AWS_PROFILE=financecoach`.
   Check with `aws sts get-caller-identity`.
3. In the Bedrock console (us-east-2), make sure Anthropic Claude models are enabled for the account.

The current account is on the AWS free plan, which only allows us-east-2 (Ohio) for EC2, ECS,
RDS, ELB, ECR, Secrets Manager and Lambda. Keep `aws_region = "us-east-2"`.

## Create everything

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # edit values
terraform init
terraform plan
terraform apply
```

The ECS service starts before any image exists, so its first tasks fail until CI pushes one.

## Fill in the API keys

Terraform creates the secret with generated values for JWT_SECRET, JASYPT_ENCRYPTOR_PASSWORD and
METRICS_TOKEN, and CHANGE_ME for the rest. Write the complete JSON (all keys) once:

```bash
SECRET=$(terraform output -raw app_secret_name)
CURRENT=$(aws secretsmanager get-secret-value --secret-id "$SECRET" --query SecretString --output text)
# Edit the CHANGE_ME values, keep the generated ones:
echo "$CURRENT" > /tmp/app-secret.json && ${EDITOR:-nano} /tmp/app-secret.json
aws secretsmanager put-secret-value --secret-id "$SECRET" --secret-string file:///tmp/app-secret.json
rm /tmp/app-secret.json
```

Tasks read secrets when they start, so redeploy (or push to main) after changing them.

## Connect GitHub Actions

With the default `use_github_oidc = false` (required on the AWS free plan, which forbids OIDC
providers), add repository secrets `AWS_ACCESS_KEY_ID` (`terraform output -raw github_deploy_access_key_id`)
and `AWS_SECRET_ACCESS_KEY` (`terraform output -raw github_deploy_secret_access_key`).

With `use_github_oidc = true`, instead add `AWS_DEPLOY_ROLE_ARN` (`terraform output -raw github_deploy_role_arn`).
If you use a region other than us-east-2, also add a repository variable `AWS_REGION`.
Then push to `main`: tests run, the image is built and pushed to ECR, and the service is updated.

## Custom domain + HTTPS

1. Set `domain_name = "api.example.com"` and apply.
2. At your DNS provider, add the CNAME from `terraform output acm_validation_record`,
   and a CNAME from your domain to `terraform output -raw alb_dns_name`.
3. Once ACM shows the certificate as Issued, set `enable_https = true` and apply.
   HTTP then redirects to HTTPS.

## After deploying

- Stripe webhook endpoint: `<api_url>/api/subscriptions/webhook`, with events
  checkout.session.completed, customer.subscription.created/updated/deleted,
  invoice.payment_succeeded and invoice.payment_failed. Put its signing secret in STRIPE_WEBHOOK_SECRET.
- Health check: `curl <api_url>/actuator/health`
- Logs: `aws logs tail /ecs/finance-coach-backend --follow`

## Tear down

`terraform destroy` removes everything, including the database (no final snapshot) and the export bucket.

State is stored locally in `terraform.tfstate` and contains the generated secrets. Keep it safe and never commit it.
