locals {
  container_name = "${var.project}-backend"
  api_url        = var.enable_https ? "https://${var.domain_name}" : "http://${aws_lb.main.dns_name}"

  # Secrets injected as environment variables from Secrets Manager
  app_secrets = [
    for key in local.app_secret_keys : {
      name      = key
      valueFrom = "${aws_secretsmanager_secret.app.arn}:${key}::"
    }
  ]
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.project}-backend"
  retention_in_days = 7
}

resource "aws_ecs_cluster" "main" {
  name = "${var.project}-cluster"

  setting {
    name  = "containerInsights"
    value = "disabled" # extra cost; CloudWatch logs + /actuator metrics are enough here
  }
}

# Initial task definition. CI registers new revisions with each image; Terraform doesn't
# roll the service back to this one (see ignore_changes on the service).
resource "aws_ecs_task_definition" "app" {
  family                   = "${var.project}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64" # CI builds linux/amd64 images
  }

  container_definitions = jsonencode([{
    name      = local.container_name
    image     = "${aws_ecr_repository.app.repository_url}:latest"
    essential = true

    portMappings = [{ containerPort = 8090, protocol = "tcp" }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${aws_db_instance.main.db_name}" },
      { name = "DB_USERNAME", value = aws_db_instance.main.username },
      { name = "AWS_REGION", value = var.aws_region },
      { name = "S3_EXPORT_BUCKET", value = aws_s3_bucket.exports.bucket },
      { name = "APP_FRONTEND_URL", value = var.frontend_url },
      { name = "APP_API_URL", value = local.api_url },
      { name = "CLAUDE_MODEL", value = var.claude_model },
      { name = "PLAID_ENVIRONMENT", value = var.plaid_environment },
      { name = "SENDGRID_FROM_EMAIL", value = var.sendgrid_from_email },
      { name = "STRIPE_PREMIUM_MONTHLY_PRICE_ID", value = var.stripe_price_ids.premium_monthly },
      { name = "STRIPE_PREMIUM_YEARLY_PRICE_ID", value = var.stripe_price_ids.premium_yearly },
      { name = "STRIPE_PRO_MONTHLY_PRICE_ID", value = var.stripe_price_ids.pro_monthly },
      { name = "STRIPE_PRO_YEARLY_PRICE_ID", value = var.stripe_price_ids.pro_yearly },
    ]

    secrets = concat(local.app_secrets, [{
      name      = "DB_PASSWORD"
      valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::"
    }])

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])
}

resource "aws_ecs_service" "app" {
  name            = "${var.project}-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  # Spring Boot needs a while to start before health checks count
  health_check_grace_period_seconds = 180

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true # outbound internet without a NAT gateway; inbound is ALB-only
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = local.container_name
    container_port   = 8090
  }

  # Roll back automatically if a new deployment never becomes healthy
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [task_definition] # CI deploys new revisions
  }

  depends_on = [aws_lb_listener.http]
}
