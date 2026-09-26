output "userservice_url" {
  description = "Địa chỉ gọi UserService"
  value       = "http://localhost:${var.userservice_port}"
}

output "login_command" {
  description = "Lệnh login thử"
  value       = "curl -X POST localhost:${var.userservice_port}/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"alice\",\"password\":\"123456\"}'"
}
