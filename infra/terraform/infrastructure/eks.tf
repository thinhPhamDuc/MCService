# EKS ở chế độ Auto Mode: AWS tự quản lý node (tạo, scale, vá bảo mật, thay mới),
# kèm sẵn Load Balancer controller, EBS CSI, VPC CNI, Pod Identity agent.
# Team chỉ cần lo phần workload -> bớt rất nhiều việc vận hành so với tự quản node group.

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.26"

  name               = local.name
  kubernetes_version = var.kubernetes_version

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets # node và pod nằm trong private subnet

  # API server: truy cập nội bộ VPC + từ internet nhưng CHỈ từ IP văn phòng/VPN
  endpoint_private_access      = true
  endpoint_public_access       = true
  endpoint_public_access_cidrs = var.eks_public_access_cidrs

  # Quyền vào cluster quản lý bằng EKS access entry (IAM), không dùng aws-auth ConfigMap
  enable_cluster_creator_admin_permissions = true
  access_entries = {
    for arn in var.cluster_admin_principal_arns : arn => {
      principal_arn = arn
      policy_associations = {
        admin = {
          policy_arn = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
          access_scope = {
            type = "cluster"
          }
        }
      }
    }
  }

  compute_config = {
    enabled    = true
    node_pools = ["general-purpose", "system"]
  }

  # Log của control plane (ai gọi API gì, lúc nào) -> audit
  enabled_log_types                      = ["api", "audit", "authenticator"]
  cloudwatch_log_group_retention_in_days = var.log_retention_days

  deletion_protection = var.environment == "prod"

  # Container Insights: thu log stdout của mọi pod + metrics CPU/RAM đẩy lên CloudWatch
  # (vai trò giống Filebeat + Elasticsearch ở Bài 4, nhưng do AWS quản lý)
  addons = {
    amazon-cloudwatch-observability = {
      pod_identity_association = [{
        role_arn        = aws_iam_role.cloudwatch_agent.arn
        service_account = "cloudwatch-agent"
      }]
    }
  }
}
