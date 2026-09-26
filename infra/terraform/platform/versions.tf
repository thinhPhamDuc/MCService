terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.3"
    }
  }

  backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
      Stack       = "platform"
    }
  }
}

# Stack này tách riêng khỏi "infrastructure" vì provider helm cần cluster ĐÃ TỒN TẠI để kết nối.
# Gộp chung 1 stack thì lần apply đầu tiên (chưa có cluster) sẽ lỗi.
data "aws_eks_cluster" "this" {
  name = "${var.project}-${var.environment}"
}

provider "helm" {
  kubernetes = {
    host                   = data.aws_eks_cluster.this.endpoint
    cluster_ca_certificate = base64decode(data.aws_eks_cluster.this.certificate_authority[0].data)

    # Lấy token ngắn hạn qua AWS CLI mỗi lần chạy, không lưu kubeconfig/credential cố định
    exec = {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", data.aws_eks_cluster.this.name, "--region", var.region]
    }
  }
}
