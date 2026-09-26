# Các add-on "nền tảng" dùng chung cho mọi service. Ghim version chart để lần apply nào
# cũng cài đúng phiên bản đó (nâng cấp = sửa version + review PR, không bao giờ tự nhảy).
#
# Ranh giới: Terraform chỉ cài các add-on này. Mọi object của ỨNG DỤNG (Deployment, Service,
# Ingress, ExternalSecret...) do ArgoCD đồng bộ từ GitOps repo, không nằm trong Terraform.

# metrics-server: cung cấp số liệu CPU/RAM của pod -> HorizontalPodAutoscaler cần cái này để tự scale
resource "helm_release" "metrics_server" {
  name       = "metrics-server"
  repository = "https://kubernetes-sigs.github.io/metrics-server/"
  chart      = "metrics-server"
  version    = "3.14.0"
  namespace  = "kube-system"
}

# External Secrets Operator: đồng bộ secret từ AWS Secrets Manager -> Kubernetes Secret.
# Quyền AWS của nó đã được cấp qua Pod Identity ở stack infrastructure
# (namespace "external-secrets", service account "external-secrets").
resource "helm_release" "external_secrets" {
  name             = "external-secrets"
  repository       = "https://charts.external-secrets.io"
  chart            = "external-secrets"
  version          = "2.11.0"
  namespace        = "external-secrets"
  create_namespace = true

  values = [yamlencode({
    serviceAccount = {
      name = "external-secrets"
    }
  })]
}

# ArgoCD: GitOps. Liên tục so sánh GitOps repo với cluster và tự đồng bộ.
# Deploy = merge PR vào GitOps repo. Không ai (kể cả CI) cần quyền kubectl vào production.
resource "helm_release" "argocd" {
  name             = "argocd"
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-cd"
  version          = "10.9.2"
  namespace        = "argocd"
  create_namespace = true

  values = [yamlencode({
    # Prod: chạy nhiều replica để ArgoCD không là điểm chết duy nhất của quy trình deploy
    controller = {
      replicas = var.environment == "prod" ? 2 : 1
    }
    server = {
      replicas = var.environment == "prod" ? 2 : 1
    }
    repoServer = {
      replicas = var.environment == "prod" ? 2 : 1
    }
  })]
}
