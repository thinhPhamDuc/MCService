variable "identifier" {
  description = "Tên RDS instance"
  type        = string
}

variable "db_name" {
  description = "Tên database tạo sẵn"
  type        = string
}

variable "vpc_id" {
  description = "VPC chứa DB"
  type        = string
}

variable "db_subnet_group_name" {
  description = "DB subnet group (các subnet riêng cho database)"
  type        = string
}

variable "allowed_cidr_blocks" {
  description = "Các dải IP được phép kết nối vào port 5432"
  type        = list(string)
}

variable "engine_major_version" {
  description = "PostgreSQL major version (giữ giống môi trường dev: postgres:16-alpine)"
  type        = string
  default     = "16"
}

variable "instance_class" {
  description = "Loại máy của RDS"
  type        = string
}

variable "allocated_storage" {
  description = "Dung lượng ổ đĩa ban đầu (GB)"
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "Dung lượng tối đa được tự nới (GB)"
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "Bật bản sao dự phòng ở AZ khác"
  type        = bool
}

variable "backup_retention_days" {
  description = "Số ngày giữ bản backup tự động (point-in-time recovery)"
  type        = number
}

variable "deletion_protection" {
  description = "Chặn xoá DB (và tạo final snapshot khi xoá)"
  type        = bool
}
