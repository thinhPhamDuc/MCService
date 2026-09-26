# GitHub Actions đăng nhập AWS bằng OIDC: mỗi lần chạy, GitHub cấp 1 token ngắn hạn,
# AWS kiểm tra token đó đến từ đúng repo + đúng branch rồi mới cho assume role.
# => KHÔNG có access key dài hạn nào phải lưu trong GitHub Secrets (lộ key là thảm hoạ).

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "github_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Chỉ workflow chạy trên branch main của đúng repo mới được push image
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_ci" {
  name               = "${var.project}-github-ci"
  assume_role_policy = data.aws_iam_policy_document.github_trust.json
}

# Least privilege: CI chỉ được push image vào đúng 3 repository.
# CI KHÔNG có quyền vào cluster: việc deploy do ArgoCD (bên trong cluster) tự kéo từ Git.
data "aws_iam_policy_document" "github_ci" {
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
    resources = [for repo in aws_ecr_repository.service : repo.arn]
  }
}

resource "aws_iam_role_policy" "github_ci" {
  name   = "ecr-push"
  role   = aws_iam_role.github_ci.id
  policy = data.aws_iam_policy_document.github_ci.json
}
