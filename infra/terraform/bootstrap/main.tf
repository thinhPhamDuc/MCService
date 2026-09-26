# Stack 0 – Bootstrap: tạo S3 bucket chứa Terraform state cho các stack còn lại.
# Chạy MỘT LẦN DUY NHẤT cho mỗi AWS account. State của chính stack này để ở local
# (bài toán con gà - quả trứng: chưa có bucket thì không thể lưu state vào bucket).

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
      Stack     = "bootstrap"
    }
  }
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "tfstate" {
  # Thêm account id để tên bucket là duy nhất toàn cầu
  bucket = "${var.project}-tfstate-${data.aws_caller_identity.current.account_id}"

  # Xoá nhầm bucket này = mất toàn bộ state = Terraform "quên" hết hạ tầng đang chạy
  lifecycle {
    prevent_destroy = true
  }
}

# Versioning: lỡ ghi hỏng state vẫn khôi phục được phiên bản trước
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

# State chứa thông tin nhạy cảm (endpoint, ARN, đôi khi cả secret) -> bắt buộc mã hoá
resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Dọn các phiên bản state cũ sau 90 ngày cho đỡ tốn tiền
resource "aws_s3_bucket_lifecycle_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    id     = "expire-old-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}
