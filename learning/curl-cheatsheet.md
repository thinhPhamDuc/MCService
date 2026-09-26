# Curl Cheatsheet – Test toàn bộ hệ thống

> Tất cả lệnh gọi vào hệ thống chạy bằng Docker Compose (port 8081 / 8082 / 8083).
> User mẫu: `alice / 123456`, `bob / 123456`.

---

## 0. Kiểm tra service còn sống

```bash
curl localhost:8081/actuator/health    # UserService
curl localhost:8082/actuator/health    # OrderService
curl localhost:8083/actuator/health    # PaymentService
```

Kết quả mong đợi: `{"status":"UP"}`

---

## 1. UserService (8081) – Đăng nhập

**Login thành công:**

```bash
curl -X POST localhost:8081/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"123456"}'
```

→ `{"userId":1,"username":"alice","token":"..."}`

**Lưu token vào biến** (bắt buộc; mỗi lần mở terminal mới phải chạy lại):

```bash
TOKEN=$(curl -s -X POST localhost:8081/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
echo $TOKEN
```

**Login sai mật khẩu** → 401:

```bash
curl -i -X POST localhost:8081/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"sai"}'
```

**Kiểm tra token** (API mà OrderService gọi nội bộ):

```bash
curl localhost:8081/auth/validate -H "Authorization: Bearer $TOKEN"     # → {"userId":1,"username":"alice"}
curl -i localhost:8081/auth/validate -H "Authorization: Bearer sai"     # → 401
```

> 💡 Mỗi lần login, token cũ **bị thay bằng token mới**. Login lại alice ở terminal khác thì `$TOKEN` cũ sẽ báo 401.

---

## 2. OrderService (8082) – Mua hàng (luồng chính)

**Tạo đơn** (tự chạy cả luồng: validate token → tạo order → thanh toán):

```bash
curl -X POST localhost:8082/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"productName":"iPhone","quantity":2,"price":1500000}'
```

→ `201`, `"status":"PAID"`, `totalAmount = quantity × price`.

**Tạo đơn với requestId tự đặt** (dễ tìm trên Kibana; `-i` để thấy header `X-Request-Id` trong response):

```bash
curl -i -X POST localhost:8082/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: test-001' \
  -d '{"productName":"iPhone","quantity":1,"price":2000000}'
```

**Đơn vượt hạn mức 10.000.000** (lỗi nghiệp vụ) → `"status":"PAYMENT_FAILED"`:

```bash
curl -X POST localhost:8082/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Car","quantity":1,"price":99000000}'
```

**Danh sách đơn của mình** (mới nhất lên đầu):

```bash
curl localhost:8082/orders -H "Authorization: Bearer $TOKEN"
```

**Xem 1 đơn theo id:**

```bash
curl localhost:8082/orders/1 -H "Authorization: Bearer $TOKEN"
```

### Các trường hợp lỗi

```bash
# Không gửi token → 401
curl -i -X POST localhost:8082/orders -H 'Content-Type: application/json' \
  -d '{"productName":"a","quantity":1,"price":1}'

# Token sai → 401
curl -i -X POST localhost:8082/orders -H "Authorization: Bearer sai" \
  -H 'Content-Type: application/json' -d '{"productName":"a","quantity":1,"price":1}'

# JSON hỏng → 400
curl -i -X POST localhost:8082/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{hong'

# Đơn không tồn tại → 404
curl -i localhost:8082/orders/99999 -H "Authorization: Bearer $TOKEN"
```

**Xem đơn của người khác → 404** (bob không xem được đơn của alice):

```bash
BOB_TOKEN=$(curl -s -X POST localhost:8081/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -i localhost:8082/orders/1 -H "Authorization: Bearer $BOB_TOKEN"
```

---

## 3. PaymentService (8083) – Thanh toán

Bình thường OrderService gọi PaymentService, nhưng có thể **gọi thẳng** để test riêng (không cần token):

```bash
# Thanh toán thành công
curl -X POST localhost:8083/payments -H 'Content-Type: application/json' \
  -d '{"orderId":100,"userId":1,"amount":50000}'

# Vượt hạn mức → status FAILED
curl -X POST localhost:8083/payments -H 'Content-Type: application/json' \
  -d '{"orderId":101,"userId":1,"amount":20000000}'

# Xem payment theo id
curl localhost:8083/payments/1

# Payment không tồn tại → 404
curl -i localhost:8083/payments/99999
```

> ❓ **Câu hỏi suy nghĩ:** bạn vừa tạo payment cho `orderId=100`, một đơn **không hề tồn tại**. Ai cũng gọi thẳng được PaymentService.
> Trên production chặn chuyện này thế nào? (Liên hệ tình huống 8 Bài 3: có nên mở `ports` cho paymentservice không?)
>
> ✍️ Trả lời:
> ```
>
> ```

---

## 4. Giả lập sự cố (Chaos)

```bash
# Xem cấu hình hiện tại (không truyền tham số = không đổi gì)
curl -X POST localhost:8083/admin/chaos

# Payment chậm 5 giây → OrderService timeout sau 3 giây
curl -X POST 'localhost:8083/admin/chaos?delayMs=5000'

# 50% request thanh toán lỗi 500
curl -X POST 'localhost:8083/admin/chaos?errorRate=0.5'

# 100% lỗi
curl -X POST 'localhost:8083/admin/chaos?errorRate=1'

# ✅ TẮT toàn bộ giả lập (nhớ chạy sau khi test xong!)
curl -X POST 'localhost:8083/admin/chaos?delayMs=0&errorRate=0'
```

> ⚠️ Cấu hình chaos lưu trong RAM: container PaymentService restart là tự về 0.
> Nếu đang scale nhiều instance, mỗi lệnh chỉ áp dụng cho **1 instance** nhận được request đó.

**Tắt hẳn một service:**

```bash
docker compose stop paymentservice     # tạo đơn → PAYMENT_FAILED (Connection refused)
docker compose start paymentservice

docker compose stop userservice        # tạo đơn → 503
docker compose start userservice
```

---

## 5. Kiểm tra dữ liệu trong DB

```bash
docker compose exec user-db    psql -U app -d user_db    -c "select id, username, token from users;"
docker compose exec order-db   psql -U app -d order_db   -c "select id, user_id, total_amount, status, payment_id, failure_reason from orders order by id;"
docker compose exec payment-db psql -U app -d payment_db -c "select id, order_id, amount, status, message from payments order by id;"
```

---

## 6. Bắn nhiều đơn một lúc (tạo dữ liệu cho Kibana)

```bash
# 10 đơn, requestId bulk-1 ... bulk-10
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "bulk-$i → %{http_code}\n" -X POST localhost:8082/orders \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "X-Request-Id: bulk-$i" \
    -d "{\"productName\":\"item-$i\",\"quantity\":1,\"price\":$((RANDOM * 10))}"
done
```

Bật `errorRate=0.5` trước khi chạy → có một mớ đơn vừa thành công vừa thất bại để luyện điều tra trên Kibana.

---

## 7. Tra nhanh

| Service | Method | Endpoint | Cần token? | Ghi chú |
|---|---|---|---|---|
| User | POST | `/auth/login` | ❌ | Trả về token |
| User | GET | `/auth/validate` | ✅ | Dùng nội bộ bởi OrderService |
| Order | POST | `/orders` | ✅ | Tạo đơn + thanh toán |
| Order | GET | `/orders` | ✅ | Danh sách đơn của mình |
| Order | GET | `/orders/{id}` | ✅ | Chỉ xem được đơn của mình |
| Payment | POST | `/payments` | ❌ | Thường do OrderService gọi |
| Payment | GET | `/payments/{id}` | ❌ | |
| Payment | POST | `/admin/chaos` | ❌ | `delayMs`, `errorRate` |
| Tất cả | GET | `/actuator/health` | ❌ | Healthcheck |

| Trạng thái order | Ý nghĩa |
|---|---|
| `PAID` | Thanh toán thành công |
| `PAYMENT_FAILED` + `paymentId` có giá trị | Payment từ chối (lỗi nghiệp vụ, vd vượt hạn mức) |
| `PAYMENT_FAILED` + `paymentId = null` | Không gọi được Payment (chết / timeout / lỗi 500) |
