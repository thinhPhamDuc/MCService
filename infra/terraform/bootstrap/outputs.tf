output "state_bucket" {
  description = "Điền giá trị này vào 'bucket' trong các file *.s3.tfbackend"
  value       = aws_s3_bucket.tfstate.bucket
}
