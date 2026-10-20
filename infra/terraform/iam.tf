locals {
  account_id = data.aws_caller_identity.current.account_id
}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------------------
# Execution role: used by ECS to pull the image, write logs and inject secrets
# ---------------------------------------------------------------------------
resource "aws_iam_role" "execution" {
  name               = "${var.project}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.app.arn,
      aws_db_instance.main.master_user_secret[0].secret_arn,
    ]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "read-app-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# ---------------------------------------------------------------------------
# Task role: what the running app may call (Bedrock for the AI coach, S3 exports)
# ---------------------------------------------------------------------------
resource "aws_iam_role" "task" {
  name               = "${var.project}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "task" {
  statement {
    sid     = "InvokeClaude"
    actions = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
    resources = [
      # Cross-region inference profiles route to the model in several US regions
      "arn:aws:bedrock:*::foundation-model/anthropic.*",
      "arn:aws:bedrock:${var.aws_region}:${local.account_id}:inference-profile/*",
    ]
  }

  statement {
    sid       = "Exports"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${aws_s3_bucket.exports.arn}/exports/*"]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "app-permissions"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# ---------------------------------------------------------------------------
# GitHub Actions deploy credentials. Either an OIDC role (no long-lived keys; only the
# main branch of var.github_repo can assume it) or, where OIDC providers can't be created,
# an IAM user with an access key. Both get the same narrow policy: push this image,
# register task definitions and update this service.
# ---------------------------------------------------------------------------
resource "aws_iam_openid_connect_provider" "github" {
  count           = var.use_github_oidc ? 1 : 0
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1", "1c58a3a8518e8759bf075b76b750d4f2df264fcd"]
}

data "aws_iam_policy_document" "github_assume" {
  count = var.use_github_oidc ? 1 : 0

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [one(aws_iam_openid_connect_provider.github[*].arn)]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  count              = var.use_github_oidc ? 1 : 0
  name               = "${var.project}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume[0].json
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [aws_ecr_repository.app.arn]
  }

  statement {
    sid       = "TaskDefinitions"
    actions   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
    resources = ["*"] # these actions don't support resource-level permissions
  }

  statement {
    sid       = "UpdateService"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = [aws_ecs_service.app.id]
  }

  statement {
    sid       = "PassTaskRoles"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.execution.arn, aws_iam_role.task.arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  count  = var.use_github_oidc ? 1 : 0
  name   = "deploy-backend"
  role   = aws_iam_role.github_deploy[0].id
  policy = data.aws_iam_policy_document.github_deploy.json
}

# Fallback when OIDC isn't available. The secret key is kept in Terraform state
# (local, gitignored) and shown via a sensitive output, to be copied into GitHub secrets.
resource "aws_iam_user" "github_deploy" {
  count = var.use_github_oidc ? 0 : 1
  name  = "${var.project}-github-deploy"
}

resource "aws_iam_user_policy" "github_deploy" {
  count  = var.use_github_oidc ? 0 : 1
  name   = "deploy-backend"
  user   = aws_iam_user.github_deploy[0].name
  policy = data.aws_iam_policy_document.github_deploy.json
}

resource "aws_iam_access_key" "github_deploy" {
  count = var.use_github_oidc ? 0 : 1
  user  = aws_iam_user.github_deploy[0].name
}
