environment = "prod"

# Prod: mỗi AZ 1 NAT -> 1 AZ sập thì 2 AZ còn lại vẫn ra internet được
single_nat_gateway = false

eks_public_access_cidrs = ["203.0.113.0/24"] # thay bằng IP văn phòng / VPN

# cluster_admin_principal_arns = ["arn:aws:iam::123456789012:role/AWSReservedSSO_DevOpsAdmin_xxxx"]

db_instance_class        = "db.t4g.medium"
db_multi_az              = true
db_backup_retention_days = 14
db_deletion_protection   = true

log_retention_days = 90
