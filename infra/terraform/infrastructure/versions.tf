terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Cấu hình backend truyền qua -backend-config=envs/<env>.s3.tfbackend
  # => cùng một code, mỗi môi trường một file state riêng
  backend "s3" {}
}

provider "aws" {
  region = var.region

  # Mọi tài nguyên đều được gắn tag -> lọc chi phí theo project/môi trường trong AWS Billing
  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
      Stack       = "infrastructure"
    }
  }
}
