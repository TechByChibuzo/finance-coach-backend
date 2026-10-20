resource "aws_ecr_repository" "app" {
  name                 = "${var.project}-backend"
  image_tag_mutability = "MUTABLE" # CI also moves the :latest tag
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Keep the 10 most recent images (enough to roll back), delete older ones
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
