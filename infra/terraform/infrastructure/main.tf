data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name     = "${var.project}-${var.environment}"
  azs      = slice(data.aws_availability_zones.available.names, 0, var.az_count)
  services = toset(["user", "order", "payment"])

  # Tính sẵn dải IP các subnet (xem sơ đồ trong network.tf)
  public_subnets   = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, i)]
  private_subnets  = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, i + 1)]
  database_subnets = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, i + 100)]
}
