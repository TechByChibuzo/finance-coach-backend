variable "aws_region" {
  description = "Region for everything. The free-plan account only allows us-east-2; us.anthropic.* Bedrock profiles work there."
  type        = string
  default     = "us-east-2"
}

variable "project" {
  description = "Name prefix for resources"
  type        = string
  default     = "finance-coach"
}

variable "github_repo" {
  description = "GitHub repository (owner/name) allowed to deploy via OIDC"
  type        = string
  default     = "TechByChibuzo/finance-coach-backend"
}

variable "use_github_oidc" {
  description = <<-EOT
    true: GitHub Actions assumes an IAM role via OIDC (no stored keys; preferred).
    false: a dedicated IAM user with an access key stored in GitHub secrets. The free-plan
    account's service control policy forbids creating OIDC identity providers.
  EOT
  type        = bool
  default     = false
}

variable "domain_name" {
  description = "Custom API domain, e.g. api.aifinancecoach.dev. Leave empty to use the ALB's own DNS name over HTTP."
  type        = string
  default     = ""
}

variable "enable_https" {
  description = "Turn on the HTTPS listener. Set to true only after the ACM validation DNS record (see outputs) is in place."
  type        = bool
  default     = false
}

variable "frontend_url" {
  description = "Frontend URL used in emails and Stripe redirects"
  type        = string
  default     = "https://finance-coach-frontend.vercel.app"
}

variable "claude_model" {
  description = "Bedrock model or inference-profile ID for the AI coach"
  type        = string
  default     = "us.anthropic.claude-sonnet-4-5-20250929-v1:0"
}

variable "plaid_environment" {
  description = "Plaid environment: sandbox or production"
  type        = string
  default     = "sandbox"
}

variable "sendgrid_from_email" {
  description = "Sender address for emails (must be verified in SendGrid)"
  type        = string
  default     = ""
}

variable "stripe_price_ids" {
  description = "Stripe price IDs applied to the subscription plans at startup"
  type = object({
    premium_monthly = string
    premium_yearly  = string
    pro_monthly     = string
    pro_yearly      = string
  })
  default = {
    premium_monthly = ""
    premium_yearly  = ""
    pro_monthly     = ""
    pro_yearly      = ""
  }
}

variable "task_cpu" {
  description = "Fargate task CPU units (512 = 0.5 vCPU)"
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Fargate task memory in MiB. The JVM is capped at 512 MiB heap by the Dockerfile."
  type        = number
  default     = 1024
}

variable "db_instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.t4g.micro"
}
