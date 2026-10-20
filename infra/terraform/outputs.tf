output "api_url" {
  description = "Base URL of the API"
  value       = local.api_url
}

output "alb_dns_name" {
  description = "Point your API domain's CNAME at this"
  value       = aws_lb.main.dns_name
}

output "github_deploy_role_arn" {
  description = "OIDC mode: set as the AWS_DEPLOY_ROLE_ARN secret in GitHub"
  value       = one(aws_iam_role.github_deploy[*].arn)
}

output "github_deploy_access_key_id" {
  description = "Access-key mode: set as the AWS_ACCESS_KEY_ID secret in GitHub"
  value       = one(aws_iam_access_key.github_deploy[*].id)
}

output "github_deploy_secret_access_key" {
  description = "Access-key mode: set as the AWS_SECRET_ACCESS_KEY secret in GitHub"
  value       = one(aws_iam_access_key.github_deploy[*].secret)
  sensitive   = true
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "app_secret_name" {
  description = "Secrets Manager secret to fill in with API keys"
  value       = aws_secretsmanager_secret.app.name
}

output "acm_validation_record" {
  description = "Add this CNAME at your DNS provider, then set enable_https = true"
  value = var.domain_name == "" ? null : {
    for o in aws_acm_certificate.api[0].domain_validation_options : o.domain_name => {
      name  = o.resource_record_name
      type  = o.resource_record_type
      value = o.resource_record_value
    }
  }
}

output "metrics_token" {
  description = "Bearer token for scraping /actuator/prometheus"
  value       = random_password.metrics_token.result
  sensitive   = true
}
