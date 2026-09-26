# Bài 4 – Log tập trung với Elasticsearch + Kibana

> **Mục tiêu:** khi khách báo "đơn hàng lỗi", bạn mở **1 màn hình** (Kibana), gõ **1 câu query**,
> thấy toàn bộ hành trình của request đó qua cả 3 service, và tìm ra service nào gây lỗi, lỗi gì, dòng code nào.

---

## 1. Vì sao cần log tập trung?

Hiện tại để debug bạn phải làm thế này:

```bash
docker compose logs orderservice   | grep ...
docker compose logs paymentservice | grep ...
docker compose logs userservice    | grep ...
```

3 service thì còn chịu được. Nhưng thực tế thường có **30 service × 3 instance mỗi service, chạy trên 10 máy**, và container bị xoá thì log cũng mất theo. Vì thế cần **gom log của tất cả về một chỗ** để tìm kiếm được.

## 2. Bức tranh tổng thể

```
┌─────────────┐  stdout   ┌──────────────────────────────┐   đọc file   ┌──────────┐   gửi   ┌───────────────┐   query   ┌────────┐
│ OrderService│ ────────▶ │ Docker lưu log ra file JSON: │ ───────────▶ │ Filebeat │ ──────▶ │ Elasticsearch │ ◀──────── │ Kibana │ ◀── bạn
│ UserService │           │ /var/lib/docker/containers/  │              │ (shipper)│         │ (lưu + index) │           │  (UI)  │
│ PaymentSvc  │           │   <id>/<id>-json.log         │              └──────────┘         └───────────────┘           └────────┘
└─────────────┘           └──────────────────────────────┘
```

| Thành phần | Vai trò | Ví von |
|---|---|---|
| **App** | Chỉ việc **in log ra stdout**, không cần biết log đi đâu | Người viết thư |
| **Docker** | Lưu stdout của mỗi container thành file (lệnh `docker compose logs` cũng đọc từ file này) | Hộp thư |
| **Filebeat** | Đọc các file log đó, gắn thêm thông tin (tên container...) rồi gửi đi | Người đưa thư |
| **Elasticsearch** | Lưu trữ và đánh index để tìm kiếm cực nhanh | Kho lưu trữ + mục lục |
| **Kibana** | Giao diện web để tìm kiếm và vẽ biểu đồ | Thủ thư |

> ❓ **Tại sao app không gửi thẳng log vào Elasticsearch?**
> Nếu làm vậy, app phải biết địa chỉ ES. Khi ES chết thì app bị treo hoặc mất log, và muốn đổi sang hệ thống log khác phải sửa code cả 30 service.
> App chỉ in ra stdout, phần vận chuyển log là việc của hạ tầng. Đây là nguyên tắc số 11 của 12-Factor App: *"Treat logs as event streams"*.

## 3. Log dạng text và log dạng JSON

Log **text** (hiện tại): đọc bằng mắt thì dễ, nhưng máy khó tách ra các field:
```
2026-09-24T16:48:57.846+07:00  WARN [demo-limit] 15566 --- [PaymentService] [nio-8083-exec-3] c.a.p.payment.PaymentController : Payment declined orderId=2 ...
```

Log **JSON theo chuẩn ECS** (Elastic Common Schema): mỗi thông tin nằm ở một field riêng, nên lọc được chính xác:
```json
{
  "@timestamp": "2026-09-25T03:53:43.267Z",
  "log": { "level": "WARN", "logger": "com.app.userservice.auth.AuthController" },
  "service": { "name": "UserService" },
  "message": "Login failed for username=alice",
  "requestId": "test-ecs"
}
```
Khi có lỗi, log còn có thêm `error.type`, `error.message`, `error.stack_trace`.

Với JSON, bạn query được `log.level : "ERROR" and service.name : "PaymentService"` thay vì phải grep và cầu may.

> Code đã hỗ trợ sẵn. Spring Boot tự chuyển sang log JSON khi có biến môi trường `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.
> Chạy trên IntelliJ (không có biến này) thì log vẫn là text dễ đọc. Đây lại là nguyên tắc cấu hình bằng env của Bài 3.

---

## 4. Thực hành

> ⚠️ **Chuẩn bị:** ES và Kibana khá tốn RAM. Vào Docker Desktop → Settings → Resources, chỉnh **Memory ≥ 6 GB**.

### Bước 1 – Bật log JSON cho 3 service

Code vừa được cập nhật (thêm `GlobalExceptionHandler`), nên bạn cần **build lại image**.

Trong `docker-compose.yml`, thêm vào **cả 3 service** (`userservice`, `orderservice`, `paymentservice`):

```yaml
    environment:
      # ... giữ nguyên các biến cũ ...
      LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs
    labels:
      logging: "elk"        # đánh dấu: "hãy thu log của container này"
```

Chạy và kiểm tra:
```bash
docker compose up -d --build
docker compose logs --tail=5 userservice    # phải thấy từng dòng là JSON
```

> ❓ Vì sao cần `labels: logging: "elk"`? Vì Filebeat đọc log của **mọi** container trên máy, kể cả Postgres và chính ES.
> Label này giúp Filebeat chỉ giữ lại log của 3 app.

### Bước 2 – Thêm Elasticsearch và Kibana vào compose

Thêm vào phần `services:` (thụt lề ngang hàng với `userservice:`):

```yaml
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    environment:
      discovery.type: single-node          # chỉ 1 node, không tìm cluster
      xpack.security.enabled: "false"      # ⚠️ TẮT bảo mật: chỉ dùng khi học ở local, TUYỆT ĐỐI không làm trên production
      ES_JAVA_OPTS: "-Xms512m -Xmx512m"    # giới hạn RAM cho ES (ES cũng là app Java!)
    ports:
      - "9200:9200"
    volumes:
      - es-data:/usr/share/elasticsearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -s http://localhost:9200/_cluster/health | grep -qE '\"status\":\"(green|yellow)\"'"]
      interval: 10s
      retries: 30

  kibana:
    image: docker.elastic.co/kibana/kibana:8.17.0
    environment:
      ELASTICSEARCH_HOSTS: http://elasticsearch:9200
    ports:
      - "5601:5601"
    depends_on:
      elasticsearch:
        condition: service_healthy
```

Và thêm `es-data:` vào phần `volumes:` ở cuối file.

> 📌 ES, Kibana và Filebeat **phải dùng cùng phiên bản** (ở đây là 8.17.0).

Kiểm tra:
```bash
docker compose up -d elasticsearch kibana
curl localhost:9200                       # ES trả về JSON có "tagline" : "You Know, for Search"
```
Mở http://localhost:5601. Kibana mất khoảng 1 phút để khởi động, lúc đó sẽ hiện "Kibana server is not ready yet", bạn cứ chờ.

### Bước 3 – Thêm Filebeat

**3a.** Tạo file `MCService/filebeat/filebeat.yml`:

```yaml
filebeat.inputs:
  - type: container                       # đọc định dạng log của Docker
    paths:
      - /var/lib/docker/containers/*/*.log

processors:
  # 1. Gắn thêm thông tin container (tên, label...) vào mỗi dòng log
  - add_docker_metadata:
      host: "unix:///var/run/docker.sock"
  # 2. Bỏ log của container không có label logging=elk
  - drop_event:
      when:
        not:
          equals:
            container.labels.logging: "elk"
  # 3. Log của app là JSON nằm trong field "message" -> tách ra thành các field riêng
  - decode_json_fields:
      fields: ["message"]
      target: ""
      overwrite_keys: true
      add_error_key: true

output.elasticsearch:
  hosts: ["http://elasticsearch:9200"]
```

**3b.** Thêm service vào compose:

```yaml
  filebeat:
    image: docker.elastic.co/beats/filebeat:8.17.0
    user: root                                     # cần quyền đọc log của Docker
    command: ["filebeat", "-e", "--strict.perms=false"]
    volumes:
      - ./filebeat/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro
      - /var/lib/docker/containers:/var/lib/docker/containers:ro   # file log của các container
      - /var/run/docker.sock:/var/run/docker.sock:ro               # để hỏi Docker tên/label container
    depends_on:
      elasticsearch:
        condition: service_healthy
```

> ❓ Có 2 dòng mount đường dẫn **của máy host** vào container. Trên Mac, Docker thực chất chạy trong một máy ảo Linux,
> nên `/var/lib/docker/containers` là đường dẫn **trong máy ảo đó**, không phải trên ổ Mac của bạn.

Chạy:
```bash
docker compose up -d filebeat
docker compose logs --tail=20 filebeat     # không có dòng ERROR là ổn
```

### Bước 4 – Xem log trên Kibana

1. Tạo vài order (thành công và thất bại) để có log.
2. Vào Kibana → ☰ Menu → **Discover**.
3. Bấm **Create data view**, điền **Index pattern** là `filebeat-*`, **Timestamp field** là `@timestamp`, rồi Save.
4. Bên trái, bấm ➕ để thêm các cột: `service.name`, `log.level`, `requestId`, `message`.
5. Góc phải trên, chọn khoảng thời gian **Last 15 minutes**.

Đến đây bạn đã có một màn hình log chung cho cả 3 service. 🎉

### Bước 5 – Các câu KQL cần nhớ

Gõ vào ô search của Discover:

| Mục đích | KQL |
|---|---|
| Toàn bộ hành trình của 1 request | `requestId : "demo-ok"` |
| Tất cả lỗi | `log.level : "ERROR"` |
| Cảnh báo và lỗi của 1 service | `service.name : "OrderService" and log.level : ("WARN" or "ERROR")` |
| Log có nhắc tới order số 5 | `message : "orderId=5"` |
| Chỉ log có stack trace | `error.stack_trace : *` |
| Lỗi theo loại exception | `error.type : "java.lang.IllegalStateException"` |

> 💡 Khi tạo order, bạn tự gửi header `-H "X-Request-Id: <tên-dễ-nhớ>"` để tìm cho dễ.
> Nếu không gửi thì bạn lấy `requestId` từ response header `X-Request-Id` (xem bằng `curl -i`).

---

## 5. Tình huống điều tra bằng Kibana

> Làm giống Bài 3: **đoán trước**, sau đó chỉ dùng **Kibana** để điều tra (không dùng `docker compose logs`).

### Tình huống 4.1 — Lần theo 1 request
Tạo một order **thành công** với header `X-Request-Id: trace-1`, rồi query `requestId : "trace-1"`.
- Có bao nhiêu dòng log? Mỗi dòng thuộc service nào?
- Sắp xếp theo thời gian rồi **kể lại câu chuyện** của request này. Service nào chạy trước, gọi đến service nào?
- Từ lúc OrderService nhận request đến lúc xong mất bao nhiêu mili-giây? Service nào chiếm nhiều thời gian nhất?

**✍️ Trả lời:**
```

```

### Tình huống 4.2 — "Đơn của tôi bị lỗi, mã đơn là X"
Bật lỗi ngẫu nhiên: `curl -X POST 'localhost:8083/admin/chaos?errorRate=0.5'`, rồi tạo khoảng 10 order **không gửi** `X-Request-Id`.
Chọn một order có trạng thái `PAYMENT_FAILED`, giả sử khách chỉ cho bạn **mã đơn** của nó.
- Làm sao từ **mã đơn** tìm ra **requestId**? (Gợi ý: query theo `message`)
- Từ requestId, tìm tiếp **stack trace** ở PaymentService. Exception là gì, ném ra ở class nào, dòng nào?
- Với cùng requestId đó, OrderService ghi log gì?

**✍️ Trả lời:**
```

```

### Tình huống 4.3 — Service nào đang "ốm"?
Vẫn để `errorRate=0.5`, tạo thêm order. Vào **Discover**, query `log.level : "ERROR"`, rồi ở cột bên trái bấm vào field `service.name` để xem **thống kê top values**.
- Service nào sinh nhiều lỗi nhất?
- **Chú ý:** mỗi lần thanh toán lỗi thì **cả** PaymentService **và** OrderService đều ghi log ERROR. Vậy service nào là **thủ phạm**, service nào là **nạn nhân**? Nhìn vào đâu để phân biệt?

**✍️ Trả lời:**
```

```

### Tình huống 4.4 — Service chết hẳn thì log nói gì?
Chạy `docker compose stop userservice` rồi tạo order.
- Query `requestId` của request đó. Có log nào từ **UserService** không? Vì sao?
- Vậy khi một service **chết hẳn**, bạn dựa vào đâu để biết? Chỉ nhìn log thì có đủ không?

(Nhớ `docker compose start userservice` sau khi làm xong.)

**✍️ Trả lời:**
```

```

### Tình huống 4.5 — Timeout
Bật `curl -X POST 'localhost:8083/admin/chaos?delayMs=5000&errorRate=0'` rồi tạo 1 order với `X-Request-Id: slow-1`.
- Query `requestId : "slow-1"`. Nhìn cột thời gian của từng dòng log.
- Dòng log "Payment done" của PaymentService xuất hiện **trước hay sau** dòng log lỗi của OrderService? Cách nhau bao nhiêu giây?
- Liên hệ với **tình huống 7 của Bài 3**: giờ bạn đã chứng minh được bằng log rằng khách bị trừ tiền mà đơn vẫn báo lỗi.

**✍️ Trả lời:**
```

```

---

## 6. Gỡ lỗi thường gặp

| Triệu chứng | Nguyên nhân hay gặp | Cách kiểm tra |
|---|---|---|
| `elasticsearch` bị Exited (137) | Thiếu RAM, bị OOM kill | Tăng Memory trong Docker Desktop |
| Kibana báo "not ready yet" rất lâu | ES chưa healthy | `docker compose ps elasticsearch`, `curl localhost:9200` |
| Tạo data view mà không thấy `filebeat-*` | Filebeat chưa gửi được gì | `docker compose logs filebeat`, `curl 'localhost:9200/_cat/indices?v'` |
| Filebeat chạy nhưng không có log | Không đọc được file, hoặc thiếu label | `docker compose exec filebeat ls /var/lib/docker/containers` (phải thấy các thư mục id) |
| Có log nhưng không có field `requestId` / `service.name` | Chưa bật `LOGGING_STRUCTURED_FORMAT_CONSOLE`, hoặc chưa build lại | `docker compose logs --tail=3 orderservice` có phải JSON không? |
| Discover trống trơn | Chọn sai khoảng thời gian | Chọn "Last 1 hour" |

---

## 7. Câu hỏi nộp bài

1. Tại sao app nên log ra **stdout** thay vì ghi vào file `/app/logs/app.log` bên trong container?
2. Nếu `elasticsearch` chết 10 phút rồi sống lại, log trong 10 phút đó có mất không? (Gợi ý: Filebeat đọc từ đâu, và nó có ghi nhớ đã đọc đến đâu không?)
3. Ta đã tắt `xpack.security`. Nếu để nguyên cấu hình này và mở port 9200 ra internet thì chuyện gì có thể xảy ra?
4. `requestId` hiện do code tự làm. Ngoài đời người ta dùng **OpenTelemetry / distributed tracing** (trace id, span id). Bạn thử tìm hiểu xem nó cung cấp thêm được gì so với cách của mình.

**✍️ Trả lời:**
```
1.
2.
3.
4.
```

---

## ✅ Checklist

- [ ] Bước 1 – 3 service log ra JSON
- [ ] Bước 2 – ES + Kibana chạy, `curl localhost:9200` OK
- [ ] Bước 3 – Filebeat chạy, có index `filebeat-*`
- [ ] Bước 4 – Thấy log của 3 service trên Discover
- [ ] TH 4.1 – Lần theo 1 request
- [ ] TH 4.2 – Từ mã đơn tìm ra stack trace
- [ ] TH 4.3 – Thủ phạm và nạn nhân
- [ ] TH 4.4 – Service chết hẳn
- [ ] TH 4.5 – Timeout và mất nhất quán dữ liệu
- [ ] Câu hỏi nộp bài
