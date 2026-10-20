resource "aws_db_subnet_group" "main" {
  name       = "${var.project}-db"
  subnet_ids = aws_subnet.private[*].id
}

# Postgres 16 with pgvector available; the app enables the extension at startup (schema.sql).
# The master password is generated and stored by RDS in Secrets Manager, so it never
# appears in Terraform state.
resource "aws_db_instance" "main" {
  identifier     = "${var.project}-db"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name                     = "financecoach"
  username                    = "fcadmin"
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period    = 1
  auto_minor_version_upgrade = true

  # Demo project: allow `terraform destroy` without a final snapshot
  deletion_protection = false
  skip_final_snapshot = true
}
