# Một RDS PostgreSQL cho MỘT service (database-per-service).
# Module được gọi 3 lần (user / order / payment) để 3 DB giống hệt nhau về cấu hình.

resource "aws_security_group" "this" {
  name_prefix = "${var.identifier}-"
  description = "PostgreSQL ${var.identifier}"
  vpc_id      = var.vpc_id

  lifecycle {
    create_before_destroy = true
  }
}

# Chỉ mở 5432 cho dải IP của private subnet (nơi các pod chạy). Không có rule egress, không public.
resource "aws_vpc_security_group_ingress_rule" "postgres" {
  for_each = toset(var.allowed_cidr_blocks)

  security_group_id = aws_security_group.this.id
  description       = "PostgreSQL from ${each.value}"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
}

resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.identifier}-"
  family      = "postgres${var.engine_major_version}"

  # Bắt buộc kết nối SSL (dữ liệu giữa app và DB được mã hoá trên đường truyền)
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # Ghi log mọi query chạy lâu hơn 500ms -> tìm query chậm
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier = var.identifier

  engine         = "postgres"
  engine_version = var.engine_major_version # chỉ ghi major -> AWS tự chọn bản minor mới nhất
  instance_class = var.instance_class

  storage_type          = "gp3"
  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage # tự nới ổ đĩa khi gần đầy
  storage_encrypted     = true

  db_name  = var.db_name
  username = "app_admin"
  # RDS tự sinh mật khẩu, lưu vào Secrets Manager và tự xoay vòng.
  # Mật khẩu KHÔNG bao giờ xuất hiện trong code hay Terraform state.
  manage_master_user_password = true

  db_subnet_group_name   = var.db_subnet_group_name
  vpc_security_group_ids = [aws_security_group.this.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false

  # Multi-AZ: có bản sao đồng bộ ở AZ khác, AZ chính sập thì tự failover trong 1-2 phút
  multi_az = var.multi_az

  backup_retention_period = var.backup_retention_days
  backup_window           = "18:00-19:00"         # UTC = 01:00-02:00 giờ Việt Nam (ít traffic)
  maintenance_window      = "sun:19:00-sun:20:00" # UTC = 02:00-03:00 sáng thứ Hai giờ Việt Nam
  copy_tags_to_snapshot   = true

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = !var.deletion_protection
  final_snapshot_identifier = var.deletion_protection ? "${var.identifier}-final" : null

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  auto_minor_version_upgrade      = true

  # Thay đổi cấu hình chờ đến maintenance window, tránh restart DB giữa giờ cao điểm
  apply_immediately = false
}
