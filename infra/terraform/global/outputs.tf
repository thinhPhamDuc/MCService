output "ecr_repository_urls" {
  description = "URL các ECR repository, dùng trong CI và manifest"
  value       = { for name, repo in aws_ecr_repository.service : name => repo.repository_url }
}

output "github_ci_role_arn" {
  description = "Role mà GitHub Actions assume (dùng trong aws-actions/configure-aws-credentials)"
  value       = aws_iam_role.github_ci.arn
}
