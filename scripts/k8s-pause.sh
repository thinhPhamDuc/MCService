#!/usr/bin/env bash
# Tạm dừng MCService trên K8s để nhường RAM khi code bằng Docker Compose.
# Giữ lại: cluster, ArgoCD, Envoy Gateway, dữ liệu Postgres (PVC).
# Bật lại: ./scripts/k8s-resume.sh
set -euo pipefail

CONTEXT=docker-desktop
NS=mcservice
APP=mcservice-local

# Chỉ chạy trên cluster local, tránh lỡ tay tắt nhầm cluster khác
current=$(kubectl config current-context)
if [ "$current" != "$CONTEXT" ]; then
  echo "❌ Context hiện tại là '$current', không phải '$CONTEXT'. Dừng lại." >&2
  exit 1
fi

# 1. Tắt auto-sync TRƯỚC: nếu không, selfHeal thấy replicas lệch Git sẽ bật pod lên lại ngay
echo "▶ Tắt auto-sync của ArgoCD app '$APP'"
kubectl patch application "$APP" -n argocd --type merge \
  -p '{"spec":{"syncPolicy":{"automated":null}}}'

# 2. Tắt 3 service + 3 Postgres. PVC không bị đụng tới nên dữ liệu vẫn còn
echo "▶ Scale mọi Deployment trong namespace '$NS' về 0"
kubectl scale deploy -n "$NS" --all --replicas=0

echo "▶ Đợi các pod tắt hẳn..."
kubectl wait pod -n "$NS" --all --for=delete --timeout=120s >/dev/null 2>&1 || true

echo
kubectl get deploy -n "$NS"
echo
echo "✅ Đã tạm dừng. Merge vào main vẫn được CI build image, nhưng K8s sẽ KHÔNG deploy."
echo "   Bật lại: ./scripts/k8s-resume.sh"
