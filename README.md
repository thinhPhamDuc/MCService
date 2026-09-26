# MCService – Microservices demo (User / Order / Payment)

| Service        | Port | Chức năng                                         |
|----------------|------|---------------------------------------------------|
| UserService    | 8081 | `POST /auth/login`, `GET /auth/validate`          |
| OrderService   | 8082 | `POST /orders`, `GET /orders`, `GET /orders/{id}` |
| PaymentService | 8083 | `POST /payments`, `POST /admin/chaos`             |

Tất cả service đều có `GET /actuator/health`.

## Luồng mua hàng

```
Client ──login──▶ UserService ──▶ token
Client ──POST /orders (Bearer token)──▶ OrderService
            OrderService ──GET /auth/validate──▶ UserService
            OrderService ──lưu order PENDING
            OrderService ──POST /payments──▶ PaymentService
            OrderService ──cập nhật PAID / PAYMENT_FAILED
```

Mọi request có header `X-Request-Id` (tự sinh nếu không gửi) và được chuyển tiếp sang service khác,
nên log của cả 3 service cho cùng 1 request đều có chung `requestId`.

## Test nhanh

User mẫu: `alice / 123456`, `bob / 123456`

```bash
TOKEN=$(curl -s -X POST localhost:8081/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"123456"}' | jq -r .token)

curl -X POST localhost:8082/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"productName":"iPhone","quantity":1,"price":2000000}'
```

## Giả lập sự cố

| Kịch bản                          | Cách làm                                                  | Kết quả                               |
|-----------------------------------|-----------------------------------------------------------|---------------------------------------|
| Lỗi nghiệp vụ                     | Đặt đơn tổng tiền > 10.000.000                            | Order `PAYMENT_FAILED`                |
| Payment chậm (timeout)            | `curl -X POST 'localhost:8083/admin/chaos?delayMs=5000'`  | Order `PAYMENT_FAILED` sau 3s         |
| Payment lỗi 500                   | `curl -X POST 'localhost:8083/admin/chaos?errorRate=1'`   | Order `PAYMENT_FAILED`                |
| Tắt PaymentService                | Stop service                                              | Order `PAYMENT_FAILED` (Connection refused) |
| Tắt UserService                   | Stop service                                              | `503 Service Unavailable`             |
| Tắt giả lập                       | `curl -X POST 'localhost:8083/admin/chaos?delayMs=0&errorRate=0'` |                               |

## Cấu hình qua biến môi trường (dùng khi deploy)

| Biến                                   | Service | Ý nghĩa                                 |
|----------------------------------------|---------|-----------------------------------------|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | tất cả | Đổi từ H2 sang Postgres       |
| `SERVICES_USER_URL`                    | Order   | Địa chỉ UserService                     |
| `SERVICES_PAYMENT_URL`                 | Order   | Địa chỉ PaymentService                  |
| `PAYMENT_SIMULATE_DELAY_MS`, `PAYMENT_SIMULATE_ERROR_RATE` | Payment | Giả lập sự cố ngay lúc khởi động |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` | tất cả | Log dạng JSON (ECS) để đẩy vào Elasticsearch/Kibana |
