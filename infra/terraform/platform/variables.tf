variable "project" {
  description = "Tên project"
  type        = string
  default     = "mcservice"
}

variable "environment" {
  description = "Tên môi trường (staging / prod)"
  type        = string
}

variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-southeast-1"
}
