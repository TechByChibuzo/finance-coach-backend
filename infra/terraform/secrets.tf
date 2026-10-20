# One secret holding the app's configuration as JSON keys. Terraform creates it with
# generated values for the app's own secrets and CHANGE_ME placeholders for third-party
# API keys; fill those in with `aws secretsmanager put-secret-value` (see README).
# Later edits are not overwritten by Terraform.

resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

resource "random_password" "jasypt_password" {
  length  = 48
  special = false
}

resource "random_password" "metrics_token" {
  length  = 40
  special = false
}

locals {
  app_secret_keys = [
    "OPENAI_API_KEY",
    "JWT_SECRET",
    "JASYPT_ENCRYPTOR_PASSWORD",
    "PLAID_CLIENT_ID",
    "PLAID_SECRET",
    "STRIPE_API_KEY",
    "STRIPE_WEBHOOK_SECRET",
    "SENDGRID_API_KEY",
    "METRICS_TOKEN",
  ]
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${var.project}/prod"
  description             = "Finance Coach backend configuration"
  recovery_window_in_days = 0 # allow delete/recreate during setup
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id

  secret_string = jsonencode({
    OPENAI_API_KEY            = "CHANGE_ME"
    JWT_SECRET                = random_password.jwt_secret.result
    JASYPT_ENCRYPTOR_PASSWORD = random_password.jasypt_password.result
    PLAID_CLIENT_ID           = "CHANGE_ME"
    PLAID_SECRET              = "CHANGE_ME"
    STRIPE_API_KEY            = "CHANGE_ME"
    STRIPE_WEBHOOK_SECRET     = "CHANGE_ME"
    SENDGRID_API_KEY          = "CHANGE_ME"
    METRICS_TOKEN             = random_password.metrics_token.result
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}
