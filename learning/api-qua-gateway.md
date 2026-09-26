# API test qua Gateway (MCService trên K8s)

> Bản K8s của `curl-cheatsheet.md`. Cheatsheet gọi Docker Compose (`localhost:8081/8082/8083`),
> còn file này gọi **một địa chỉ duy nhất** `http://mcservice.localhost`, đi qua Envoy Gateway (Bài 8).
> User mẫu: `alice / 123456`, `bob / 123456`.

---

## 0. Chuẩn bị

```bash
./scripts/k8s-resume.sh        # nếu K8s đang tạm dừng (xong thì ./scripts/k8s-pause.sh)

B=http://mcservice.localhost   # đặt biến cho gọn; mỗi terminal mới phải đặt lại
```

Kiểm tra cửa vào còn sống:
```bash
kubectl get gateway -n gateway           # PROGRAMMED True
kubectl get httproute -A                 # public-api (mcservice), argocd (argocd)
kubectl get pods -n mcservice            # 6 pod Running 1/1
curl -s -o /dev/null -w "%{http_code}\n" $B/orders    # 401 = Gateway + orderservice đã chạy
```

---

## 1. Đăng nhập: `/auth` → userservice

```bash
# Login thành công → {"userId":1,"username":"alice","token":"..."}
curl -X POST $B/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"123456"}'

# Lưu token vào biến (bắt buộc cho các lệnh bên dưới)
TOKEN=$(curl -s -X POST $B/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
echo $TOKEN

# Sai mật khẩu → 401
curl -i -X POST $B/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"sai"}'
```

> 💡 Mỗi lần login, token cũ bị thay bằng token mới. Login alice ở chỗ khác thì `$TOKEN` cũ trả 401.

---

## 2. Mua hàng: `/orders` → orderservice

```bash
# Tạo đơn → 201, "status":"PAID"
curl -X POST $B/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"productName":"iPhone","quantity":1,"price":2000000}'

# Vượt hạn mức 10.000.000 → "status":"PAYMENT_FAILED"
curl -X POST $B/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"productName":"Car","quantity":1,"price":99000000}'

# Danh sách đơn của mình (mới nhất lên đầu)
curl $B/orders -H "Authorization: Bearer $TOKEN"

# Xem 1 đơn
curl $B/orders/1 -H "Authorization: Bearer $TOKEN"

# Tự đặt requestId, -i để xem header X-Request-Id trong response (xem thêm TH 8.5)
curl -i -X POST $B/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'X-Request-Id: gw-test-001' -d '{"productName":"iPhone","quantity":1,"price":2000000}'
```

### Các trường hợp lỗi
```bash
# Không có token → 401
curl -i -X POST $B/orders -H 'Content-Type: application/json' -d '{"productName":"a","quantity":1,"price":1}'

# JSON hỏng → 400
curl -i -X POST $B/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{hong'

# Đơn không tồn tại → 404
curl -i $B/orders/99999 -H "Authorization: Bearer $TOKEN"

# Bob xem đơn của alice → 404
BOB_TOKEN=$(curl -s -X POST $B/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -i $B/orders/1 -H "Authorization: Bearer $BOB_TOKEN"
```

> Ở phần này có 2 loại 404: do **Spring Boot** trả (đơn không tồn tại) và do **Envoy** trả (path không có route).
> Phân biệt được 2 loại này là nội dung của TH 8.1.

---

## 3. API không đi qua Gateway

HTTPRoute `public-api` chỉ có 2 rule: `/auth` và `/orders`. Mọi path khác (`/payments`, `/admin/chaos`, `/actuator/...`)
**không có đường vào** từ `mcservice.localhost`. Muốn gọi thì phải đi "cửa sau" bằng port-forward, **chỉ dùng để debug**:

```bash
# Terminal 1: giữ lệnh này chạy
kubectl port-forward -n mcservice svc/paymentservice 18083:8083

# Terminal 2
curl -X POST localhost:18083/admin/chaos                              # xem cấu hình chaos hiện tại
curl -X POST 'localhost:18083/admin/chaos?delayMs=5000'               # payment chậm 5s → order timeout
curl -X POST 'localhost:18083/admin/chaos?errorRate=0.5'              # 50% thanh toán lỗi 500
curl -X POST 'localhost:18083/admin/chaos?delayMs=0&errorRate=0'      # ✅ TẮT (nhớ chạy sau khi test)
curl localhost:18083/payments/1
curl localhost:18083/actuator/health
```
Dùng port 18083 để không đụng Docker Compose (đang dùng 8083).

> ⚠️ Chaos lưu trong RAM của **1 pod**. Nếu `paymentservice` có nhiều replica, port-forward chỉ bật chaos cho 1 pod đó (TH 5.3).
> Pod restart hoặc bị thay khi deploy thì chaos tự về 0.

Tắt hẳn một service, tương đương `docker compose stop`. Làm được vì `k8s-pause.sh` đã tắt auto-sync; nếu auto-sync đang bật thì selfHeal sẽ bật lại ngay:
```bash
kubectl scale deploy/paymentservice -n mcservice --replicas=0   # tạo đơn → PAYMENT_FAILED
kubectl scale deploy/paymentservice -n mcservice --replicas=1
```

---

## 4. Xem dữ liệu trong DB (Postgres trong K8s)

```bash
kubectl exec -n mcservice deploy/user-db    -- psql -U app -d user_db    -c "select id, username from users;"
kubectl exec -n mcservice deploy/order-db   -- psql -U app -d order_db   -c "select id, user_id, total_amount, status, payment_id, failure_reason from orders order by id;"
kubectl exec -n mcservice deploy/payment-db -- psql -U app -d payment_db -c "select id, order_id, amount, status, message from payments order by id;"
```
Dữ liệu này **khác** dữ liệu của Docker Compose, vì hai bên dùng hai bộ DB riêng.

---

## 5. Bắn nhiều request

```bash
# 10 đơn liên tiếp
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "gw-$i → %{http_code}\n" -X POST $B/orders \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "X-Request-Id: gw-$i" \
    -d "{\"productName\":\"item-$i\",\"quantity\":1,\"price\":$((RANDOM * 10))}"
done

# Vòng lặp theo dõi khi deploy (TH 8.4): Ctrl+C để dừng
while true; do curl -s -o /dev/null -w "%{http_code} " -X POST $B/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice","password":"123456"}'; sleep 0.2; done
```

Xem log:
```bash
kubectl logs -n mcservice deploy/orderservice --tail=20 -f
kubectl logs -n mcservice -l app=userservice --prefix --tail=20     # mọi pod userservice
```

---

## 6. Tra nhanh

| Method | URL | Token? | Qua Gateway? | Ghi chú |
|---|---|---|---|---|
| POST | `$B/auth/login` | ❌ | ✅ | Trả token |
| GET | `$B/auth/validate` | ✅ | ⚠️ Có (nằm dưới `/auth`) | API nội bộ. Có nên public không? → TH 8.1 |
| POST | `$B/orders` | ✅ | ✅ | Tạo đơn + thanh toán |
| GET | `$B/orders` | ✅ | ✅ | Danh sách đơn của mình |
| GET | `$B/orders/{id}` | ✅ | ✅ | Chỉ xem được đơn của mình |
| POST | `/payments`, GET `/payments/{id}` | ❌ | ❌ | Chỉ port-forward (mục 3) |
| POST | `/admin/chaos` | ❌ | ❌ | Chỉ port-forward (mục 3) |
| GET | `/actuator/health` | ❌ | ❌ | K8s probe gọi thẳng vào pod |
| – | `http://argocd.localhost` | – | ✅ | UI ArgoCD (route `deploy/platform/argocd-route.yaml`) |

| So với Compose | Docker Compose | K8s qua Gateway |
|---|---|---|
| Địa chỉ | `localhost:8081` / `8082` / `8083` | `mcservice.localhost` |
| Gọi thẳng PaymentService | Được | Không (chỉ port-forward) |
| Tắt service | `docker compose stop x` | `kubectl scale deploy/x --replicas=0` |
| Xem DB | `docker compose exec x-db psql ...` | `kubectl exec deploy/x-db -- psql ...` |
| Xem log | `docker compose logs -f x` | `kubectl logs deploy/x -f` |
