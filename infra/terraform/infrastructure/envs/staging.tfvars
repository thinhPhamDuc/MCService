environment = "staging"

# Staging: giống prod về KIẾN TRÚC, nhưng nhỏ và rẻ hơn
single_nat_gateway = true

eks_public_access_cidrs = ["203.0.113.0/24"] # thay bằng IP văn phòng / VPN

db_instance_class        = "db.t4g.micro"
db_multi_az              = false
db_backup_retention_days = 3
db_deletion_protection   = false

log_retention_days = 14
