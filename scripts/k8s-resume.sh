#!/usr/bin/env bash
# Bật lại MCService trên K8s sau khi tạm dừng bằng ./scripts/k8s-pause.sh.
# ArgoCD bật lại auto-sync + selfHeal, đưa replicas về như trong Git
# và deploy luôn newTag mới nhất trên branch main (GitHub, không phải code trên máy).
set -euo pipefail

CONTEXT=docker-desktop
NS=mcservice
APP=mcservice-local
ROOT=$(cd "$(dirname "$0")/.." && pwd)

current=$(kubectl config current-context)
if [ "$current" != "$CONTEXT" ]; then
  echo "❌ Context hiện tại là '$current', không phải '$CONTEXT'. Dừng lại." >&2
  exit 1
fi

# 1. Apply lại Application từ file trong Git: automated + prune + selfHeal quay lại
echo "▶ Bật lại auto-sync cho ArgoCD app '$APP'"
kubectl apply -f "$ROOT/deploy/argocd/mcservice-local.yaml"

# Bảo ArgoCD đọc lại Git ngay, không đợi chu kỳ ~3 phút
kubectl annotate application "$APP" -n argocd argocd.argoproj.io/refresh=normal --overwrite >/dev/null

# 2. Đợi ArgoCD đưa replicas về như Git (đang là 0 do k8s-pause.sh)
echo "▶ Đợi ArgoCD sync..."
for _ in $(seq 1 60); do
  zero=$(kubectl get deploy -n "$NS" -o jsonpath='{range .items[*]}{.spec.replicas}{"\n"}{end}' | grep -c '^0$' || true)
  [ "$zero" -eq 0 ] && break
  sleep 5
done
if [ "$zero" -ne 0 ]; then
  echo "❌ Sau 5 phút vẫn còn Deployment có replicas=0. Xem UI ArgoCD: http://argocd.localhost" >&2
  exit 1
fi

# 3. Đợi từng Deployment chạy xong (Spring Boot cần ~1 phút để qua startupProbe)
echo "▶ Đợi pod Running (có thể vài phút nếu phải tải image mới)..."
for d in $(kubectl get deploy -n "$NS" -o name); do
  kubectl rollout status -n "$NS" "$d" --timeout=10m
done

# 4. Thử một request thật qua Gateway
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://mcservice.localhost/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"123456"}' || true)

echo
kubectl get deploy -n "$NS" -o wide
echo
kubectl get application "$APP" -n argocd
echo
if [ "$code" = "200" ]; then
  echo "✅ Đã bật lại. POST http://mcservice.localhost/auth/login → 200"
  echo "   Các API để test: learning/api-qua-gateway.md"
else
  echo "⚠️  Pod đã chạy nhưng login qua Gateway trả '$code' (mong đợi 200)."
  echo "   Kiểm tra: kubectl get gateway -n gateway ; kubectl get httproute -A"
fi
