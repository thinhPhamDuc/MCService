# VPC 3 tầng, trải trên 3 Availability Zone (3 trung tâm dữ liệu độc lập trong 1 region):
#
#   public   /24  : chỉ Load Balancer + NAT Gateway      (có đường ra/vào internet)
#   private  /20  : EKS node + pod                       (chỉ đi ra internet qua NAT, không ai gọi vào được)
#   database /24  : RDS                                  (không có đường ra internet)
#
# Với vpc_cidr = 10.0.0.0/16:
#   public   = 10.0.0.0/24,   10.0.1.0/24,   10.0.2.0/24
#   private  = 10.0.16.0/20,  10.0.32.0/20,  10.0.48.0/20   (~4000 IP mỗi AZ, vì mỗi pod chiếm 1 IP của VPC)
#   database = 10.0.100.0/24, 10.0.101.0/24, 10.0.102.0/24

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.7"

  name = local.name
  cidr = var.vpc_cidr
  azs  = local.azs

  public_subnets   = local.public_subnets
  private_subnets  = local.private_subnets
  database_subnets = local.database_subnets

  create_database_subnet_group = true

  enable_nat_gateway     = true
  single_nat_gateway     = var.single_nat_gateway
  one_nat_gateway_per_az = !var.single_nat_gateway

  enable_dns_hostnames = true
  enable_dns_support   = true

  # Tag để EKS biết đặt Load Balancer ở đâu:
  # ALB public (internet-facing) vào public subnet, ALB nội bộ vào private subnet
  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }

  # Flow log: ghi lại mọi kết nối mạng -> điều tra sự cố bảo mật
  enable_flow_log                                 = true
  create_flow_log_cloudwatch_log_group            = true
  create_flow_log_cloudwatch_iam_role             = true
  flow_log_cloudwatch_log_group_retention_in_days = var.log_retention_days
}
