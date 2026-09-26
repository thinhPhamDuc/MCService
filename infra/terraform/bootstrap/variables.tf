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
