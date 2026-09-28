# Thiết kế: Import CSV ~1 triệu dòng (ImportService)

> Trạng thái: **Đã duyệt** — Phase 1, 2 (2026-09-27), 3 (2026-09-28) xong. Đã làm gì + lập luận thông số: [`csv-import-implementation.md`](csv-import-implementation.md). Nhánh: `feature/csv-import`.

## 0. Quyết định đã chốt

| # | Quyết định |
|---|---|
| 1 | Kiến trúc **B**: staging table + worker (`FOR UPDATE SKIP LOCKED`) |
| 2 | Bên thứ ba = **mock**, có **bulk endpoint**; mặc định happy case (không quan tâm độ trễ/rate limit) |
| 3 | Service mới **`ImportService`**, DB **MySQL 8.4** (image multi-arch amd64/arm64 — đã kiểm tra trên Docker Hub) |
| 4 | **Java 25 (LTS) riêng cho `ImportService` + `ThirdPartyMock`**, giai đoạn 2 dùng **Virtual Thread** |

Không sửa UserService / OrderService / PaymentService — 3 service cũ vẫn Java 17.

Đã kiểm tra (2026-09-26):
- Spring Boot 4.1.1 hỗ trợ Java 17 → 26 (trang System Requirements của Spring Boot).
- `eclipse-temurin:25-jdk` và `25-jre` có bản amd64 + arm64 trên Docker Hub.
- Gradle wrapper của repo là 9.7.1; toolchain `JavaLanguageVersion.of(25)` sẽ tự tải JDK 25 như đang làm với JDK 17.

---

## 1. Giải đáp câu hỏi

### Ý 1 — Đọc/parse CSV có cách nào khác ngoài streaming?

| Cách | Mô tả | Với 1 triệu dòng |
|---|---|---|
| **Streaming** (đọc từng dòng) | `BufferedReader` + parser CSV, luôn chỉ giữ vài nghìn dòng trong RAM | ✅ RAM cố định, file 1 triệu hay 10 triệu dòng đều như nhau |
| Nạp cả file vào RAM | `Files.readAllLines` / `readAllBytes` rồi parse | ❌ File ~200 MB → thành object Java 3–5 lần ≈ 1 GB → OOMKilled trên pod K8s |
| Memory-mapped file | `FileChannel.map` — OS ánh xạ file vào bộ nhớ ảo | ⚠️ Nhanh cho file nhị phân; với CSV vẫn phải tự tách dòng, lợi ích nhỏ vì đọc đĩa không phải nút cổ chai |
| Chia file theo byte, parse song song | N thread, mỗi thread một đoạn byte, căn về đầu dòng | ⚠️ Hỏng khi CSV có ô chứa xuống dòng trong dấu `"..."`; phức tạp mà lợi ít |
| Để DB tự đọc file | MySQL `LOAD DATA LOCAL INFILE` | ✅ Nhanh nhất, nhưng phải bật `local_infile` ở server + `allowLoadLocalInfile=true` ở JDBC (có rủi ro bảo mật: server độc có thể đọc file của client), khó validate từng dòng |
| Framework | Spring Batch `FlatFileItemReader` | Bên trong vẫn là streaming + chunk; thêm nhiều bảng metadata, nặng cho bài này |

**Chọn:** streaming bằng thư viện CSV (Apache Commons CSV — xử lý đúng dấu phẩy/xuống dòng trong ô `"..."`), gom thành **chunk 1000 dòng** rồi insert. `LOAD DATA` để làm tình huống so sánh tốc độ sau.

### Ý 2 — Đọc CSV chỉ 1 thread thôi sao? Số dòng không cố định thì chia thread thế nào?

**Vì sao đọc chỉ cần 1 thread:** đọc đĩa hàng trăm MB/s, parse 1 triệu dòng mất vài giây. Chậm là ở **insert DB** (và sau đó là gọi API). Nên:

```
1 reader ──chunk 1000 dòng──▶ [hàng đợi giới hạn 10 chunk] ──▶ W writer (mặc định 6 thread) ──batch insert──▶ MySQL
```

Đúng ý bạn "batch 1000 record một lượt": reader gom 1000 dòng thành 1 chunk, writer insert cả chunk bằng **1 câu multi-row INSERT** (JDBC `rewriteBatchedStatements=true` — xem số đo mục 3).

**Mấu chốt: số thread KHÔNG phụ thuộc số dòng.**

| Sai | Đúng |
|---|---|
| Tạo 1 thread cho mỗi batch → 1 triệu dòng = 1000 thread, 10 triệu = 10.000 thread → sập | **Thread pool cố định** + **hàng đợi có giới hạn** (backpressure) |

- Số thread được quyết định bởi **tài nguyên** (connection pool DB, CPU, rate limit API) — cấu hình được.
- Số dòng chỉ quyết định **thời gian chạy**: 1 triệu hay 5 triệu dòng thì vẫn 1 reader + W writer + N worker, RAM tối đa ≈ (W + 10) chunk × 1000 dòng.
- Hàng đợi đầy → reader tự **chờ** (không đọc thêm) → không tràn RAM dù file lớn bao nhiêu.
- Muốn nhanh hơn nữa → **thêm pod** (scale ngang), không tăng thread trong 1 pod. Kiến trúc B cho phép vì các pod lấy việc qua `SKIP LOCKED` không trùng nhau.
- Nhiều file upload cùng lúc → giai đoạn 1 dùng pool riêng tối đa `import.ingest.max-concurrent-jobs` (mặc định 1), file khác xếp hàng trạng thái `QUEUED`. Giai đoạn 2 dùng chung giới hạn cho mọi job.
- Với Virtual Thread (mục 3), "cố định" không còn là số thread mà là **số việc được chạy đồng thời** (`Semaphore`) — ý tưởng giữ nguyên: giới hạn theo tài nguyên, không theo số dòng.

---

## 2. Kiến trúc tổng thể

```
Client
  │ POST /imports (multipart file)
  ▼
ImportService :8084 ───────────────────────────────────────────────┐
  │ 1. lưu file ra đĩa, tạo import_job (QUEUED) → trả 202 + jobId     │
  │                                                                   │
  │ GIAI ĐOẠN 1 — INGEST (1 reader + W writer)                        │
  │   stream CSV → validate → chunk 1000 → INSERT import_row (NEW)    │
  │                                                                   │
  │ GIAI ĐOẠN 2 — PROCESS (K worker virtual thread, sau khi gđ 1 xong)│
  │   tx ngắn: claim 500 dòng NEW (FOR UPDATE SKIP LOCKED) → PROCESSING│
  │   KHÔNG giữ transaction: 5 bulk request × 100 dòng, mỗi request   │
  │   1 virtual thread, qua Semaphore(api.max-concurrent)  ───────────┼──▶ ThirdPartyMock :8090
  │   tx ngắn: INSERT customer (bảng đích) + import_row → DONE/FAILED │     POST /v1/verify/bulk
  │                                                                   │
  │ GET /imports/{jobId} → tiến độ                                    │
  ▼                                                                   │
MySQL 8.4 (import-db) ◀──────────────────────────────────────────────┘
```

Điểm quan trọng: **không giữ transaction/row lock trong lúc gọi API** — nếu giữ, 500 dòng bị khoá suốt thời gian chờ mạng và connection pool cạn ngay.

## 3. Thread & Virtual Thread (mặc định, đều cấu hình được)

### Dùng Virtual Thread ở đâu

| Giai đoạn | Loại thread | Lý do |
|---|---|---|
| Đọc + parse CSV | Platform thread (1) | Tốn CPU, không chờ I/O → virtual thread không giúp gì |
| Insert staging | Platform thread (3) | Bị chặn bởi connection pool, không phải số thread |
| **Gọi bulk API** | **Virtual thread**, mỗi request 1 thread | Gần như toàn bộ thời gian là chờ mạng → đúng chỗ virtual thread phát huy |

Vì sao Java 25 chứ không phải 21: từ Java 24 (JEP 491), virtual thread **không còn bị "ghim" (pinning)** vào carrier thread khi gặp `synchronized` — trên Java 21, driver/thư viện dùng `synchronized` quanh I/O làm mất lợi thế của virtual thread.

### Quy tắc khi dùng Virtual Thread

1. **Không pool** virtual thread: dùng `Executors.newVirtualThreadPerTaskExecutor()`, mỗi việc tạo 1 thread mới rồi bỏ.
2. **Giới hạn đồng thời bằng `Semaphore`**, không bằng số thread: tạo được 1 triệu virtual thread không có nghĩa bên thứ ba chịu được 1 triệu request cùng lúc. Rate limit / connection pool vẫn là giới hạn thật.
3. Không giữ connection DB trong lúc chờ API (đã đảm bảo ở mục 2 — transaction ngắn).

### Bảng cấu hình mặc định

| Thành phần | Key cấu hình | Mặc định | Công thức / lý do |
|---|---|---|---|
| Reader | — | 1 | Đọc file tuần tự |
| Writer (giai đoạn 1) | `import.ingest.writers` | 6 | Đo thật (bảng bên dưới): tăng tới ~6 thì nhanh dần, 12 không nhanh hơn — MySQL đã là nút cổ chai |
| Worker (giai đoạn 2, virtual thread) | `import.process.workers` | 8 | Số chunk 500 dòng đang xử lý cùng lúc = số transaction claim/ghi đồng thời |
| Bulk request đồng thời | `import.api.max-concurrent` | 16 | `≈ số request/giây mong muốn × độ trễ API`. Happy case: độ trễ 100 ms, 16 đồng thời → 160 request/s × 100 dòng = 16.000 dòng/s → 1 triệu dòng ≈ 1–2 phút |
| Hikari pool | `spring.datasource.hikari.maximum-pool-size` | 16 | ≥ writer + worker + dư vài connection cho API tiến độ |

Muốn tăng tốc (khi bên thứ ba cho phép) chỉ cần tăng `import.api.max-concurrent` — không phải đổi kích thước thread pool.

Ước lượng giai đoạn 2 (happy case, đo thật ở Phase 3/5): ~1–3 phút, chạy chồng lên giai đoạn 1.

### Số đo giai đoạn 1 (2026-09-27)

> Lần đo sơ bộ (bảng không được làm rỗng giữa các lần). Số đo chuẩn hơn ngày 2026-09-28 (6 writer: 8,3 s) nằm ở [`csv-import-implementation.md`](csv-import-implementation.md#số-đo-2026-09-28).

1 triệu dòng (147 MB, 1000 dòng sai), MySQL 8.4 trong Docker Desktop (Testcontainers), Mac arm64 8 CPU, chunk 1000:

| Cấu hình | Thời gian | Dòng/giây |
|---|---|---|
| 3 writer, **thiếu** `rewriteBatchedStatements` | 135 s | ~7.400 |
| 1 writer | 35 s | ~28.000 |
| 3 writer | 16 s | ~60.000 |
| **6 writer (mặc định)** | **11–12 s** | **~85.000** |
| 12 writer | 11 s | ~87.000 — không nhanh hơn 6 |

Bài học:
- `rewriteBatchedStatements` quyết định nhiều hơn số thread (×8). Tham số này đặt ở `spring.datasource.hikari.data-source-properties`, **không** đặt trong URL: khi test, Testcontainers thay URL → tham số trong URL biến mất (đây chính là nguyên nhân của dòng 135 s).
- Quá điểm bão hoà (~6), thêm thread không nhanh hơn — nút cổ chai là MySQL, không phải số thread.

## 4. Mô hình dữ liệu (MySQL)

**`import_job`** — `id` (PK), `file_name`, `status` (`QUEUED` → `INGESTING` → `PROCESSING` → `COMPLETED` / `COMPLETED_WITH_ERRORS` / `FAILED`), `total_rows`, `processed_rows`, `failed_rows`, `invalid_rows`, `created_at`, `finished_at`.

**`import_row`** (staging) — PK `(job_id, row_no)`; 15 cột dữ liệu thô; `status` (`NEW` / `PROCESSING` / `DONE` / `FAILED`), `attempts`, `error`, `claimed_at`. Cột `status` của CSV lưu thành `customer_status` để không trùng tên. Index `(status, job_id, row_no)` cho câu claim (worker lấy dòng `NEW` của mọi job).

**`customer`** (bảng đích) — `id` AUTO_INCREMENT, các cột dữ liệu đã chuẩn hoá + kết quả từ API (`verified`, `risk_score`), `UNIQUE(job_id, row_no)`.

Idempotent: insert bảng đích dùng `INSERT ... ON DUPLICATE KEY UPDATE id = id` (không dùng `INSERT IGNORE` vì nó nuốt cả lỗi khác như sai kiểu dữ liệu) → chạy lại không sinh bản ghi trùng.

Dòng không hợp lệ (sai số cột, email sai định dạng…) ghi `FAILED` ngay ở giai đoạn 1 với `error`, không gọi API.

## 5. CSV mẫu & API mock (⚠️ cần bạn duyệt — đang là giả định)

CSV 15 cột (khách hàng): `external_id, full_name, email, phone, date_of_birth, gender, address, city, country, postal_code, company, job_title, annual_income, signup_date, status`.

ThirdPartyMock (service Spring Boot nhỏ, port 8090):

```
POST /v1/verify/bulk        (tối đa 100 item/request)
  body: [{ "externalId", "fullName", "email", "phone", "country" }, ...]
  → [{ "externalId": "...", "verified": true, "riskScore": 17 }, ...]
```

Mặc định trả ngay, không lỗi. Có sẵn nút chỉnh (giống `/admin/chaos` của PaymentService) để về sau làm tình huống: độ trễ, % lỗi 5xx, trả 429 khi vượt rate limit.

## 6. Xử lý lỗi & chạy lại

| Tình huống | Xử lý |
|---|---|
| API lỗi 5xx / timeout | Tối đa 3 lần gọi (1 lần đầu + 2 lần thử lại), chờ 200 ms → 400 ms; hết lượt → dòng `FAILED` + lý do |
| API trả 429 | Chờ theo `Retry-After` rồi thử lại, không tính vào số lần retry |
| Pod chết giữa giai đoạn 2 | Dòng `PROCESSING` có `claimed_at` quá 5 phút được coi là bỏ dở → worker khác claim lại |
| Pod chết giữa giai đoạn 1 | Job `INGESTING` quá hạn → `FAILED`, upload lại (giai đoạn 1 chỉ vài chục giây; muốn resume cần lưu file trên volume bền — ghi nhận là giới hạn) |
| Chạy lại dòng lỗi | `POST /imports/{jobId}/retry-failed` đặt `FAILED` (lỗi API) → `NEW` |

## 7. API của ImportService

| Method | Path | Mô tả |
|---|---|---|
| POST | `/imports` | Upload CSV (multipart) → `202 {jobId}` |
| GET | `/imports/{jobId}` | Trạng thái + tiến độ + thời gian chạy |
| GET | `/imports/{jobId}/errors?limit=100` | Danh sách dòng lỗi |
| POST | `/imports/{jobId}/retry-failed` | Xử lý lại dòng lỗi |

Lưu ý cấu hình: Spring mặc định giới hạn upload **1 MB** → phải đặt `spring.servlet.multipart.max-file-size` / `max-request-size` (ví dụ 500 MB).

## 8. Kế hoạch triển khai (sau khi duyệt)

Claude code; bạn tự chạy compose / deploy.

| Phase | Nội dung |
|---|---|
| 1 | Khung `ImportService` + `ThirdPartyMock` (Gradle, **Java 25** toolchain, Dockerfile multi-arch `eclipse-temurin:25-jdk` → `25-jre`), thêm `import-db` (MySQL 8.4), `importservice`, `thirdparty-mock` vào `docker-compose.yml` |
| 2 | Giai đoạn 1: upload, streaming parse, validate, batch insert staging |
| 3 | Giai đoạn 2: worker virtual thread claim `SKIP LOCKED`, bulk API client + `Semaphore` + retry, ghi bảng đích |
| 4 | API tiến độ / lỗi / retry-failed |
| 5 | Đo toàn bộ (giai đoạn 1 + 2) với 1 triệu dòng, điền lại số liệu mục 3. (Script `scripts/gen-customers-csv.py` và số đo giai đoạn 1 đã làm sớm ở Phase 2) |
| 6 | (Để sau) Manifest K8s trong `deploy/base`, thêm service vào matrix CI (lưu ý: job `test` phải dùng JDK 25 cho ImportService, JDK 17 cho service cũ) |

## 9. Cần bạn duyệt

- [ ] Mục 1–4: kiến trúc, số thread mặc định, mô hình dữ liệu
- [ ] Mục 5: bộ 15 cột CSV và response của mock có phù hợp với dữ liệu thật của bạn không?
- [ ] Vị trí file thiết kế: `docs/csv-import-design.md`
- [ ] Phase 6 (K8s + CI) để sau có ổn không?
