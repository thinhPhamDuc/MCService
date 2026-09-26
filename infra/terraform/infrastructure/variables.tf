variable "project" {
  description = "Tên project, dùng làm tiền tố cho tên tài nguyên"
  type        = string
  default     = "mcservice"
}

variable "environment" {
  description = "Tên môi trường"
  type        = string

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment phải là 'staging' hoặc 'prod'."
  }
}

variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-southeast-1"
}

# ---------------------------------------------------------------- Network

variable "vpc_cidr" {
  description = "Dải IP của VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "az_count" {
  description = "Số Availability Zone sử dụng"
  type        = number
  default     = 3
}

variable "single_nat_gateway" {
  description = "true: 1 NAT cho cả VPC (rẻ, dùng cho staging). false: mỗi AZ 1 NAT (chịu lỗi AZ, dùng cho prod)"
  type        = bool
}

# ---------------------------------------------------------------- EKS

variable "kubernetes_version" {
  description = "Phiên bản Kubernetes (kiểm tra các phiên bản EKS đang hỗ trợ trước khi đổi)"
  type        = string
  default     = "1.35"
}

variable "eks_public_access_cidrs" {
  description = "Các dải IP được gọi Kubernetes API từ internet (IP văn phòng / VPN)"
  type        = list(string)
}

variable "cluster_admin_principal_arns" {
  description = "IAM role/user được quyền admin cluster (vd: role SSO của team DevOps)"
  type        = list(string)
  default     = []
}

# ---------------------------------------------------------------- Database

variable "db_instance_class" {
  description = "Loại máy RDS"
  type        = string
}

variable "db_multi_az" {
  description = "Bật Multi-AZ cho RDS"
  type        = bool
}

variable "db_backup_retention_days" {
  description = "Số ngày giữ backup RDS"
  type        = number
}

variable "db_deletion_protection" {
  description = "Chặn xoá RDS"
  type        = bool
}

# ---------------------------------------------------------------- Observability

variable "log_retention_days" {
  description = "Số ngày giữ log trên CloudWatch"
  type        = number
}
