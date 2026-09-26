# ECR nằm ở stack "global" (không theo môi trường) vì nguyên tắc BUILD ONCE, DEPLOY MANY:
# CI build image 1 lần, cùng một image đó được deploy lên staging rồi promote lên prod.
# Nếu mỗi môi trường build lại image riêng thì thứ chạy trên prod KHÔNG PHẢI thứ đã test trên staging.

resource "aws_ecr_repository" "service" {
  for_each = var.services

  name = "${var.project}/${each.key}"

  # Tag đã push thì không ghi đè được -> tag "a3f9c1e" luôn trỏ đúng 1 image duy nhất
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "service" {
  for_each = aws_ecr_repository.service

  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Giữ lại ${var.image_retention_count} image mới nhất"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.image_retention_count
      }
      action = {
        type = "expire"
      }
    }]
  })
}
