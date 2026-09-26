# Bài 8 – Một cửa vào duy nhất: Gateway API (thay cho Ingress)

> **Mục tiêu:** bỏ hẳn `kubectl port-forward`. Khách chỉ biết **một địa chỉ**:
>
> ```
> http://mcservice.localhost/auth/login   → userservice
> http://mcservice.localhost/orders       → orderservice
> http://mcservice.localhost/payments     → ❌ không cho vào (service nội bộ)
> ```
>
> Chạy trên Docker Desktop K8s, miễn phí.

---

## 1. Vấn đề của port-forward

| | `kubectl port-forward` (Bài 5–7) | Cửa vào chung (Bài này) |
|---|---|---|
| Số địa chỉ khách phải nhớ | Mỗi service một port | **Một** domain |
| Pod bị thay (rolling update) | **Đứt kết nối** (TH 5.6) | Không đứt, vì chuyển sang pod mới |
| Chia tải nhiều pod | Không, chỉ dính vào 1 pod (TH 5.3) | **Có** |
| Dùng cho | Debug của dev | Khách hàng thật |
| Quyết định service nào public | Ai port-forward được là vào được | **Khai báo rõ ràng** trong manifest |

Trên production, đây chính là **Load Balancer + domain + HTTPS** (xem `production-design.md`, mục 2).

---

## 2. Ingress, Gateway API và chuyện `ingress-nginx` ngừng phát triển

**Ingress** là cách cũ (từ 2015) để khai báo "domain/path nào đi vào service nào". Nó cần một **controller** chạy thật sự,
và phổ biến nhất là `ingress-nginx`.

> ⚠️ **Tháng 3/2026, dự án `ingress-nginx` chính thức ngừng phát triển** (repo đã archive, không còn vá bảo mật).
> Rất nhiều công ty đang phải chuyển đổi. Bạn sẽ gặp Ingress trong các hệ thống cũ, nhưng **học mới thì nên học Gateway API**.

**Gateway API** là thế hệ tiếp theo, do chính Kubernetes phát triển. Nó giải quyết các hạn chế của Ingress:

| | Ingress | Gateway API |
|---|---|---|
| Tính năng nâng cao (chia % traffic, header...) | Mỗi controller một kiểu **annotation** riêng, đổi controller là viết lại | **Có sẵn trong chuẩn**, đổi controller vẫn giữ nguyên manifest |
| Phân quyền | 1 object gộp hết | **Tách vai trò**: team hạ tầng quản lý `Gateway`, dev quản lý `HTTPRoute` |
| Chia traffic 90/10 (canary) | Tuỳ controller | `weight:` có sẵn |

### Ba object cần biết

```
 GatewayClass  "dùng phần mềm nào làm cửa vào?"           ← cài 1 lần (ở đây: Envoy Gateway)
      │
 Gateway       "mở cửa ở port nào, cho ai gắn route vào?" ← team hạ tầng / platform
      │
 HTTPRoute     "domain + path nào đi vào service nào?"    ← dev của từng service (nằm trong GitOps)
      │
 Service → Pod
```

So với Bài 5: **Service** trả lời câu hỏi "trong cluster gọi nhau thế nào", còn **Gateway + HTTPRoute** trả lời câu hỏi "**từ ngoài** vào trong cluster thế nào".

**Envoy Gateway** là phần mềm làm cửa vào mà bài này dùng. Nó dùng **Envoy**, loại proxy mà nhiều hệ thống lớn đang chạy.

### Luồng một request

```
curl http://mcservice.localhost/orders
  │  (*.localhost tự trỏ về 127.0.0.1, không cần sửa /etc/hosts)
  ▼
localhost:80  ── Docker Desktop đưa Service type LoadBalancer ra localhost ──┐
                                                                            ▼
                                            Envoy proxy (pod, namespace envoy-gateway-system)
                                              đọc HTTPRoute: host mcservice.localhost + path /orders
                                                                            │
                                                                            ▼
                                            Service orderservice:8082 → Pod orderservice
```

---

## 3. Thực hành

### Bước 1 – Cài Envoy Gateway

Lệnh này cài cả **Gateway API CRD** lẫn controller:

```bash
kubectl apply --server-side \
  -f https://github.com/envoyproxy/gateway/releases/download/v1.9.1/install.yaml

kubectl wait --timeout=10m -n envoy-gateway-system \
  deployment/envoy-gateway --for=condition=Available
```

> Lần đầu mất vài phút để tải image (giống ArgoCD ở Bài 7). Pod ở `ContainerCreating` lâu thì xem Events trước.

✅ Kiểm tra:
```bash
kubectl get pods -n envoy-gateway-system          # envoy-gateway-xxx   1/1 Running
kubectl get crd | grep gateway.networking.k8s.io  # gatewayclasses, gateways, httproutes...
```

### Bước 2 – Mở "cửa": GatewayClass + Gateway (phần hạ tầng)

Tạo file `deploy/platform/gateway.yaml`:

```yaml
# Dùng Envoy Gateway làm phần mềm cửa vào
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: envoy
spec:
  controllerName: gateway.envoyproxy.io/gatewayclass-controller
---
apiVersion: v1
kind: Namespace
metadata:
  name: gateway
---
# Cửa vào chung: nghe HTTP port 80
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: public
  namespace: gateway
spec:
  gatewayClassName: envoy
  listeners:
    - name: http
      protocol: HTTP
      port: 80
      allowedRoutes:
        namespaces:
          from: All        # HTTPRoute ở namespace nào cũng được gắn vào (mcservice, argocd...)
```

```bash
kubectl apply -f deploy/platform/gateway.yaml
kubectl get gateway -n gateway -w        # đợi PROGRAMMED = True
```

Envoy Gateway sẽ **tự tạo** một Deployment Envoy và một Service `LoadBalancer`:

```bash
kubectl get svc -n envoy-gateway-system
# envoy-gateway-public-xxxx   LoadBalancer   ...   EXTERNAL-IP   80:3xxxx/TCP
```

✅ `curl -i http://mcservice.localhost/` trả về **404**. Như vậy là **đúng**: cửa đã mở, chỉ là chưa có route nào.

> ❓ Vì sao `deploy/platform/` nằm **ngoài** `deploy/envs/local` (thư mục mà ArgoCD đồng bộ)?
> Gợi ý: Gateway dùng chung cho **nhiều app**, và thường do một team khác quản lý.

### Bước 3 – Khai báo route cho mcservice (bài tập, đi qua GitOps)

Tạo file `deploy/envs/local/httproute.yaml`. Route nằm trong **overlay** vì domain khác nhau theo môi trường (`mcservice.localhost`, `staging.shop.com`...):

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: public-api
spec:
  parentRefs:
    - name: public          # gắn vào Gateway nào?
      namespace: gateway
  hostnames:
    - mcservice.localhost
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /auth
      backendRefs:
        - name: userservice
          port: 8081
    - matches:
        - path:
            type: PathPrefix
            value: ???        # đường dẫn của OrderService
      backendRefs:
        - name: ???
          port: ???
    # ❓ Có nên thêm rule cho /payments không? Vì sao?
```

Thêm file vào `deploy/envs/local/kustomization.yaml`:

```yaml
resources:
  - ../../base
  - httproute.yaml
```

Sau đó commit và push. **Không** `kubectl apply`, để ArgoCD tự đồng bộ:

```bash
kubectl kustomize deploy/envs/local | grep -A3 'kind: HTTPRoute'   # xem trước
git add deploy && git commit -m "deploy(local): expose auth + orders via Gateway API" && git push
```

✅ Trên giao diện ArgoCD, sơ đồ app có thêm một ô **HTTPRoute** `public-api`.

✅ Chạy luồng mua hàng **không cần port-forward**:

```bash
TOKEN=$(curl -s -X POST http://mcservice.localhost/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"123456"}' \
  | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -X POST http://mcservice.localhost/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"productName":"iPhone","quantity":1,"price":2000000}'
```

→ `"status":"PAID"` 🎉. Từ giờ, trong `curl-cheatsheet.md`, bạn thay `localhost:8081` và `localhost:8082` bằng `mcservice.localhost`.

### Bước 4 – (Tuỳ chọn) ArgoCD qua `argocd.localhost`

ArgoCD mặc định **tự chuyển HTTP sang HTTPS**. Khi đứng sau Gateway (Gateway nhận HTTP), ta tắt việc chuyển này:

```bash
kubectl -n argocd patch configmap argocd-cmd-params-cm --type merge -p '{"data":{"server.insecure":"true"}}'
kubectl -n argocd rollout restart deploy/argocd-server
```

Tạo file `deploy/platform/argocd-route.yaml`, rồi `kubectl apply`:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: argocd
  namespace: argocd          # route nằm CÙNG namespace với Service mà nó trỏ tới
spec:
  parentRefs:
    - name: public
      namespace: gateway
  hostnames:
    - argocd.localhost
  rules:
    - backendRefs:
        - name: argocd-server
          port: 80
```

✅ Mở http://argocd.localhost, không cần port-forward 8443 nữa.

> ⚠️ Việc này chỉ dùng cho **học ở local**. Trên production, giao diện quản trị như ArgoCD không mở ra internet,
> hoặc phải có HTTPS + SSO + giới hạn IP.

---

## 4. Tình huống

### TH 8.1 — Cửa nào mở, cửa nào đóng?
```bash
curl -i http://mcservice.localhost/payments/1
curl -i http://mcservice.localhost/admin/chaos -X POST
curl -i http://mcservice.localhost/actuator/health
curl -i http://mcservice.localhost/auth/validate
curl -i http://localhost/orders          # không có hostname mcservice.localhost
```
- Lệnh nào trả về 404? 404 đó do **ai** trả về: Envoy hay Spring Boot? (Gợi ý: xem header `server:` và body của response.)
- Đây là câu trả lời cuối cùng cho câu hỏi "ai cũng gọi thẳng được PaymentService" trong cheatsheet. Giải thích bằng 1 câu.
- `/auth/validate` là API **nội bộ** mà OrderService dùng, nhưng lại lọt ra ngoài vì nằm dưới `/auth`. Có nguy hiểm không? Sửa route thế nào để chỉ public đúng `/auth/login`? (Gợi ý: `type: Exact`)

**✍️ Trả lời:**
```

```

### TH 8.2 — `PathPrefix` hoạt động thế nào?
```bash
curl -i http://mcservice.localhost/auth        # ?
curl -i http://mcservice.localhost/auth/       # ?
curl -i http://mcservice.localhost/authx       # ?
curl -i http://mcservice.localhost/AUTH/login  # ?
```
- Lệnh nào khớp với rule `/auth`? `PathPrefix` so khớp **theo từng ký tự** hay **theo từng đoạn giữa các dấu `/`**?
- Có phân biệt chữ hoa, chữ thường không?

**✍️ Trả lời:**
```

```

### TH 8.3 — Chia tải thật sự (so với TH 5.3)
Trong `deploy/base/userservice.yaml`, đặt `replicas: 3`, rồi commit và push (qua GitOps). Sau đó:
```bash
for i in $(seq 1 30); do curl -s -o /dev/null -X POST http://mcservice.localhost/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"123456"}'; done
kubectl logs -n mcservice -l app=userservice --prefix | grep -c "Login success"          # tổng
kubectl logs -n mcservice -l app=userservice --prefix | grep "Login success" | cut -d' ' -f1 | sort | uniq -c   # theo pod
```
- 30 request được chia cho 3 pod thế nào? So với port-forward ở TH 5.3 thì sao?

**✍️ Trả lời:**
```

```

### TH 8.4 — 🚀 Rolling update không mất request (làm lại TH 5.6 cho đúng)
Ở TH 5.6, port-forward bị **đứt** khi pod bị thay, nên bạn không kiểm chứng được "không downtime". Giờ làm lại qua Gateway:
1. Giữ `replicas: 3` từ TH 8.3.
2. Mở một terminal chạy vòng lặp:
   ```bash
   while true; do curl -s -o /dev/null -w "%{http_code} " -X POST http://mcservice.localhost/auth/login \
     -H 'Content-Type: application/json' -d '{"username":"alice","password":"123456"}'; sleep 0.2; done
   ```
3. Đổi log trong `AuthController` (ví dụ `v2` → `v3`), rồi push. Pipeline Bài 7 sẽ tự deploy.
- Trong lúc rolling update, vòng lặp có ra mã nào khác `200` không? Nếu có thì bao nhiêu lần?
- Nếu có vài lỗi `503`: tìm hiểu vì sao pod **đang bị tắt** vẫn nhận request (gợi ý: `terminationGracePeriodSeconds`, `preStop`, graceful shutdown của Spring Boot).

**✍️ Trả lời:**
```

```

### TH 8.5 — `requestId` giờ do ai tạo?
```bash
curl -si -X POST http://mcservice.localhost/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"123456"}' | grep -i request-id
kubectl logs -n mcservice deploy/userservice --tail=3
```
- Response có header `X-Request-Id` không? Bạn **không gửi** header này, vậy ai đã tạo ra nó: Envoy hay `RequestIdFilter` trong code?
  (Gợi ý: so sánh định dạng giá trị, và đọc tài liệu Envoy về `x-request-id`.)
- Vì sao việc **tạo requestId ngay tại cửa vào** lại tốt hơn cho việc truy vết (Bài 4)?

**✍️ Trả lời:**
```

```

### TH 8.6 — Ingress và Gateway API: đọc hiểu để đi làm
Công ty bạn đang dùng hệ thống cũ, có Ingress như sau:
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: public-api
  annotations:
    nginx.ingress.kubernetes.io/limit-rps: "10"
spec:
  ingressClassName: nginx
  rules:
    - host: api.shop.com
      http:
        paths:
          - path: /orders
            pathType: Prefix
            backend:
              service:
                name: orderservice
                port:
                  number: 8082
```
- Chuyển nó sang `HTTPRoute`. Phần nào chuyển được ngay? Phần nào **không** chuyển được y nguyên? (Gợi ý: annotation `limit-rps` là tính năng riêng của nginx.)
- Vì sao việc `ingress-nginx` ngừng phát triển lại là vấn đề **bảo mật**, chứ không chỉ là chuyện "không có tính năng mới"?

**✍️ Trả lời:**
```

```

---

## 5. Câu hỏi nộp bài

1. Vẽ đường đi của request `POST http://mcservice.localhost/orders` từ `curl` đến pod orderservice, **rồi tiếp** từ orderservice đến userservice và paymentservice. Chặng nào đi qua Envoy, chặng nào **không**?
2. Vì sao `Gateway` (platform) và `HTTPRoute` (app) nên nằm ở **2 chỗ khác nhau** và do 2 nhóm người quản lý?
3. Trên AWS (`production-design.md`), vai trò của Envoy Gateway ở đây ứng với thành phần nào? Local còn thiếu gì so với production? (Gợi ý: HTTPS, domain thật, WAF)
4. Tìm hiểu: làm sao thêm HTTPS cho `mcservice.localhost`? (Gợi ý: listener `protocol: HTTPS` + `certificateRefs`, cert-manager)

**✍️ Trả lời:**
```
1.
2.
3.
4.
```

---

## ✅ Checklist

- [ ] Bước 1 – Cài Envoy Gateway
- [ ] Bước 2 – Gateway `public` PROGRAMMED, `curl mcservice.localhost` trả 404
- [ ] Bước 3 – HTTPRoute qua GitOps, luồng mua hàng chạy **không cần port-forward**
- [ ] Bước 4 – (Tuỳ chọn) ArgoCD qua `argocd.localhost`
- [ ] TH 8.1 – Cửa nào mở, cửa nào đóng
- [ ] TH 8.2 – PathPrefix
- [ ] TH 8.3 – Chia tải
- [ ] TH 8.4 – Rolling update không mất request 🚀
- [ ] TH 8.5 – requestId tại cửa vào
- [ ] TH 8.6 – Ingress và Gateway API
- [ ] Câu hỏi nộp bài
