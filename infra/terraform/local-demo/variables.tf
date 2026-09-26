variable "project" {
  description = "Tiền tố tên tài nguyên"
  type        = string
  default     = "mcservice-tf"
}

variable "userservice_image" {
  description = "Image UserService có sẵn trên máy (đã build ở Bài 3 bằng docker compose)"
  type        = string
  default     = "mcservice-userservice:latest"
}

variable "userservice_port" {
  description = "Port trên máy bạn để gọi UserService (khác 8081 để không đụng compose)"
  type        = number
  default     = 18081
}

variable "db_password" {
  description = "Mật khẩu Postgres"
  type        = string
  default     = "secret"
  sensitive   = true # Terraform sẽ ẩn giá trị này khi in plan/output
}
