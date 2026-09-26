# Database-per-service: 3 RDS instance tách biệt.
# PaymentService quá tải hay bị lỗi migration cũng không kéo UserService/OrderService chết theo.

module "postgres" {
  source   = "../modules/postgres"
  for_each = local.services

  identifier = "${local.name}-${each.key}"
  db_name    = "${each.key}_db"

  vpc_id               = module.vpc.vpc_id
  db_subnet_group_name = module.vpc.database_subnet_group_name
  allowed_cidr_blocks  = local.private_subnets

  instance_class        = var.db_instance_class
  multi_az              = var.db_multi_az
  backup_retention_days = var.db_backup_retention_days
  deletion_protection   = var.db_deletion_protection
}
