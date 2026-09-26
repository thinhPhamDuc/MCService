# Bài 3 – Docker Compose: Tình huống thực tế

> Cách làm: với mỗi tình huống → **đoán trước** → tự tay gây lỗi → quan sát → giải thích.
> Đoán sai không sao — chỗ đoán sai là chỗ học được nhiều nhất.
> Điền câu trả lời vào phần **✍️ Trả lời** của từng tình huống rồi gửi lại để chữa.

**Ưu tiên:** Tình huống **5, 7, 8** là quan trọng nhất.

---

## 🧰 Lệnh điều tra

```bash
docker compose ps                        # trạng thái container (running / exited / restarting)
docker compose logs -f <service>         # log realtime
docker compose logs --tail=50 <service>  # 50 dòng log cuối
docker compose exec <service> <lệnh>     # chạy lệnh BÊN TRONG container đang chạy
docker compose up -d <service>           # tạo lại riêng 1 service nếu config của nó thay đổi
docker compose up -d --build <service>   # build lại image rồi chạy lại
docker volume ls                         # liệt kê volume
```

Xem dữ liệu trong DB:

```bash
docker compose exec order-db   psql -U app -d order_db   -c "select id, status, failure_reason from orders;"
docker compose exec payment-db psql -U app -d payment_db -c "select id, order_id, status from payments;"
```

---

## 🟢 Cấp 1 – Mạng & cấu hình

### Tình huống 1 — "Chạy trên máy em thì được mà!"

> Đồng nghiệp copy config từ lúc chạy IntelliJ sang compose: `SERVICES_USER_URL: http://localhost:8081`.
> Deploy xong, khách không đặt được đơn nào.

**Làm:** sửa đúng dòng đó trong compose → `docker compose up -d orderservice` → tạo order.

**Đoán trước:**
- Client nhận HTTP status gì?
- Login có bị ảnh hưởng không?

**Điều tra:**
- `docker compose logs --tail=30 orderservice` → tìm dòng `Cannot reach UserService`. Lỗi là *Connection refused* hay *timeout*? Vì sao?
- Nâng cao: `docker compose exec orderservice getent hosts userservice` → in ra gì? `userservice` được phân giải thành gì?

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

---

### Tình huống 2 — Đổi port bên ngoài

> Port 8081 trên server đã bị phần mềm khác chiếm. Sếp bảo đổi UserService sang `9081`.

**Làm:** (sửa lại lỗi tình huống 1 trước) đổi `ports` của userservice thành `"9081:8081"` → `up -d`.

**Đoán trước:**
- Login bằng `localhost:9081` hay `localhost:8081`?
- OrderService có phải đổi `SERVICES_USER_URL` thành `:9081` không?

**Câu cần trả lời bằng 1 câu:** port bên trái dành cho ai, port bên phải dành cho ai?

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

---

## 🟡 Cấp 2 – Dữ liệu & vòng đời container

### Tình huống 3 — "Deploy xong mất sạch đơn hàng!"

> Sau một lần deploy, toàn bộ order cũ biến mất. Có 3 nghi phạm:
> (a) `docker compose down` (b) `docker compose down -v` (c) `docker compose up --build`

**Làm:** tạo 2 order, thử lần lượt từng nghi phạm, sau mỗi lần dùng `psql` kiểm tra dữ liệu.

**Điền bảng:**

| Lệnh         | Container | Image | Volume | Dữ liệu còn? |
|--------------|-----------|-------|--------|--------------|
| `down`       |           |       |        |              |
| `down -v`    |           |       |        |              |
| `up --build` |           |       |        |              |

**Nâng cao:** xoá dòng `volumes:` của `order-db` → `down` → `up` → chuyện gì xảy ra? Xem `docker volume ls`.

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

---

### Tình huống 4 — "Sửa code rồi mà không thấy thay đổi?"

> Bạn sửa log trong `AuthController` từ `"Login success"` → `"LOGIN OK !!!"`, chạy `docker compose up -d`.
> Login thử, log vẫn hiện chữ cũ.

**Làm:** làm đúng như trên, sau đó thử `docker compose up -d --build userservice`.

**Giải thích:** quan hệ giữa **image**, **container** và **source code** trên máy bạn? Vì sao `up -d` không tự build lại?

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

---

### Tình huống 5 — 🔥 Đổi mật khẩu DB (bẫy kinh điển)

> Team bảo mật yêu cầu đổi mật khẩu DB. Bạn sửa `POSTGRES_PASSWORD: secret` → `newpass` ở `payment-db`
> **và** `SPRING_DATASOURCE_PASSWORD` → `newpass` ở `paymentservice`. Hai bên khớp nhau.

**Làm:** sửa cả 2 chỗ → `docker compose up -d` → `docker compose ps`.

**Đoán trước:** paymentservice có chạy không?

**Điều tra:** `docker compose logs paymentservice` → tìm `password authentication failed`.
Đã đổi đúng cả 2 bên, vì sao vẫn sai mật khẩu?

> 💡 Gợi ý: biến `POSTGRES_PASSWORD` được Postgres dùng **vào lúc nào** trong vòng đời của nó? Liên hệ tình huống 3.

**Sau khi hiểu:** tìm 2 cách sửa — 1 cách **mất dữ liệu**, 1 cách **giữ dữ liệu**. Production chọn cách nào?

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
Cách sửa mất dữ liệu:
Cách sửa giữ dữ liệu:
```

---

## 🔴 Cấp 3 – Sự cố giữa các service

### Tình huống 6 — Service crash liên tục

> (Tiếp tình huống 5, paymentservice đang chết.) Sếp hỏi: "Sao container chết không tự sống lại?"

**Làm:** thêm `restart: on-failure` vào paymentservice (vẫn để sai mật khẩu) → `up -d` → chạy `docker compose ps` vài lần cách nhau vài giây.

**Quan sát:**
- Cột STATUS thay đổi thế nào?
- Restart có giải quyết được vấn đề không?
- Khi nào `restart` hữu ích, khi nào vô ích?

> Hiện tượng này trên Kubernetes gọi là `CrashLoopBackOff`.

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

---

### Tình huống 7 — 💸 "Khách bị trừ tiền mà đơn báo thất bại"

> Khách gọi điện: app báo đặt hàng thất bại nhưng tài khoản đã bị trừ tiền.

**Làm:**
1. Sửa lại mật khẩu cho paymentservice chạy bình thường.
2. Thêm env `PAYMENT_SIMULATE_DELAY_MS: 5000` cho paymentservice → `up -d paymentservice` (giả lập cổng thanh toán chậm).
3. Tạo 1 order.
4. Đợi 5 giây → chạy 2 lệnh `psql` so sánh bảng `orders` và `payments`.

**Câu hỏi:**
- Order bên OrderService trạng thái gì? Payment bên PaymentService trạng thái gì? Có khớp không?
- Vẽ timeline theo giây: lúc nào Order bỏ cuộc, lúc nào Payment ghi SUCCESS?
- Log 2 service có chung `requestId` không? Dùng nó chứng minh 2 dòng log thuộc cùng 1 request.
- **Câu mở:** là dev, bạn sẽ sửa thế nào (chỉ cần ý tưởng)? Tăng timeout có phải cách đúng không?

**✍️ Trả lời:**
```
Order status:
Payment status:
Timeline:
  0s  -
  3s  -
  5s  -
requestId chứng minh:
Ý tưởng sửa:
```

---

### Tình huống 8 — Cần thêm máy xử lý thanh toán

> Mùa sale, PaymentService quá tải. Sếp bảo chạy 3 instance.

**Làm:** `docker compose up -d --scale paymentservice=3`

**Đoán trước:** lệnh có chạy thành công không?

Nếu lỗi → đọc thông báo và tự sửa.

> 💡 Gợi ý: 3 container có cùng mở được port 8083 **trên máy bạn** không? PaymentService có thực sự cần mở port ra ngoài?

Sau khi scale được: tạo ~10 order → `docker compose logs paymentservice` (mỗi dòng có tiền tố `paymentservice-1/2/3`).
Request có chia đều cho 3 instance không? Nếu **không đều**, vì sao và cần thêm thành phần gì?

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Lỗi gặp phải & cách sửa:
Phân bổ request:
Vì sao:
```

---

## ✅ Checklist

- [ ] TH1 – localhost trong container
- [ ] TH2 – port trái / phải
- [ ] TH3 – down / down -v / volume
- [ ] TH4 – image vs container vs source
- [ ] TH5 – đổi mật khẩu DB 🔥
- [ ] TH6 – restart policy
- [ ] TH7 – mất nhất quán dữ liệu 💸
- [ ] TH8 – scale service
