variable "project" {
  description = "Tên project, dùng làm tiền tố cho tên tài nguyên"
  type        = string
  default     = "mcservice"
}

variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-southeast-1"
}

variable "services" {
  description = "Danh sách service, mỗi service một ECR repository"
  type        = set(string)
  default     = ["userservice", "orderservice", "paymentservice"]
}

variable "github_repository" {
  description = "Repo GitHub được phép push image, dạng 'owner/repo'"
  type        = string
}

variable "image_retention_count" {
  description = "Số image giữ lại trong mỗi repository"
  type        = number
  default     = 50
}
