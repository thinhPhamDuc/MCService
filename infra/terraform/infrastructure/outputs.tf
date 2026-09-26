output "cluster_name" {
  description = "Tên EKS cluster (dùng cho: aws eks update-kubeconfig --name ...)"
  value       = module.eks.cluster_name
}

output "vpc_id" {
  description = "VPC id"
  value       = module.vpc.vpc_id
}

output "databases" {
  description = "Thông tin kết nối từng DB, dùng để điền vào manifest của service (GitOps repo)"
  value = {
    for svc, db in module.postgres : svc => {
      host       = db.address
      port       = db.port
      db_name    = db.db_name
      secret_arn = db.master_user_secret_arn
    }
  }
}
