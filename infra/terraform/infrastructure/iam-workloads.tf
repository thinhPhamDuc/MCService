# Quyền AWS cho các thành phần chạy TRONG cluster, cấp qua EKS Pod Identity:
# pod nào (namespace + service account) được assume role nào. Không pod nào cầm access key.

data "aws_iam_policy_document" "pod_identity_trust" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------- CloudWatch agent

resource "aws_iam_role" "cloudwatch_agent" {
  name               = "${local.name}-cloudwatch-agent"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

resource "aws_iam_role_policy_attachment" "cloudwatch_agent" {
  role       = aws_iam_role.cloudwatch_agent.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

resource "aws_iam_role_policy_attachment" "cloudwatch_agent_xray" {
  role       = aws_iam_role.cloudwatch_agent.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXrayWriteOnlyAccess"
}

# ---------------------------------------------------------------- External Secrets Operator
# ESO đọc mật khẩu DB từ Secrets Manager rồi tạo Kubernetes Secret cho từng service.
# Chỉ được đọc ĐÚNG 3 secret của 3 DB, không đọc được secret nào khác trong account.

resource "aws_iam_role" "external_secrets" {
  name               = "${local.name}-external-secrets"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

data "aws_iam_policy_document" "external_secrets" {
  statement {
    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
    ]
    resources = [for db in module.postgres : db.master_user_secret_arn]
  }
}

resource "aws_iam_role_policy" "external_secrets" {
  name   = "read-db-secrets"
  role   = aws_iam_role.external_secrets.id
  policy = data.aws_iam_policy_document.external_secrets.json
}

resource "aws_eks_pod_identity_association" "external_secrets" {
  cluster_name    = module.eks.cluster_name
  namespace       = "external-secrets"
  service_account = "external-secrets"
  role_arn        = aws_iam_role.external_secrets.arn
}
