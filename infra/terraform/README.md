# MCService – Terraform (AWS)

Thiết kế hạ tầng production tham khảo cho 3 service. Phân tích chi tiết từng quyết định:
[`learning/production-design.md`](../../learning/production-design.md).

| Stack | Nội dung | State |
|---|---|---|
| `bootstrap/` | S3 bucket chứa Terraform state | local, chạy 1 lần |
| `global/` | ECR × 3, GitHub Actions OIDC role | `global/terraform.tfstate` |
| `infrastructure/` | VPC 3 AZ, EKS Auto Mode, RDS × 3, IAM cho workload | mỗi môi trường 1 file |
| `platform/` | metrics-server, External Secrets Operator, ArgoCD | mỗi môi trường 1 file |
| `modules/postgres/` | Module 1 RDS PostgreSQL cho 1 service | |

Thứ tự apply: `bootstrap` → `global` → `infrastructure` → `platform`. Destroy theo thứ tự ngược lại.

⚠️ Đã qua `terraform fmt` + `terraform validate`, chưa từng apply trên AWS thật. Apply sẽ phát sinh chi phí.
