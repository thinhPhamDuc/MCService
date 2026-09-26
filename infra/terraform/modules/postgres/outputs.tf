output "address" {
  description = "Hostname của DB"
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Port của DB"
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Tên database"
  value       = aws_db_instance.this.db_name
}

output "master_user_secret_arn" {
  description = "ARN của secret (username/password) trong Secrets Manager"
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}
