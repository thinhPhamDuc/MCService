# Nhật ký triển khai: Import CSV (ImportService)

> Thiết kế (vì sao chọn kiến trúc này): [`csv-import-design.md`](csv-import-design.md).
> File này ghi lại **đã làm gì ở từng Phase, luồng chạy thật trong code, và các thông số được chọn/đo như thế nào**.
> Nhánh: `feature/csv-import`.

## Tiến độ

| Phase | Nội dung | Trạng thái | Commit |
|---|---|---|---|
| 1 | Khung ImportService + ThirdPartyMock, schema MySQL, docker compose | ✅ 2026-09-27 | `0533382` |
| 2 | Upload + ingest CSV vào bảng staging | ✅ 2026-09-27 | `95fa968` (+ thông số / benchmark 2026-09-28) |
| 3 | Worker (virtual thread) gọi bulk API, ghi bảng `customer` | ⏳ | |
| 4 | API tiến độ / danh sách lỗi / retry-failed | ⏳ | |
| 5 | Đo toàn bộ giai đoạn 1 + 2 | ⏳ | |
| 6 | K8s + CI | ⏳ | |

---

## Phase 1 — Khung dự án

**Mục đích:** có 2 service chạy được, DB có sẵn bảng, để các Phase sau chỉ việc thêm logic.

| Thành phần | Đã làm |
|---|---|
| `ThirdPartyMock/` (:8090) | `POST /v1/verify/bulk` (tối đa 100 item), kết quả tính từ dữ liệu vào (gọi lại → cùng kết quả). `POST /admin/chaos` chỉnh độ trễ / % lỗi / rate limit (429 + `Retry-After`). Mặc định happy case |
| `ImportService/` (:8084) | Java 25, virtual thread cho HTTP, JdbcTemplate (không JPA), Flyway `V1__init_schema.sql` |
| Schema | `import_job` (1 dòng / file), `import_row` (staging, dữ liệu thô, PK `(job_id, row_no)`), `customer` (bảng đích, `UNIQUE(job_id, row_no)`) |
| `docker-compose.yml` | `import-db` (mysql:8.4, host port 3307), `thirdparty-mock`, `importservice` |
| Test | Chạy trên MySQL thật bằng Testcontainers (H2 không giống MySQL ở `SKIP LOCKED` / `ON DUPLICATE KEY`) |

Vấn đề gặp phải: máy dev chỉ có JDK 26 → Gradle không tìm thấy JDK 25. Sửa: thêm plugin `foojay-resolver-convention` vào `settings.gradle` để Gradle tự tải JDK 25.

---

## Phase 2 — Ingest (giai đoạn 1)

### Mục đích

Bài toán có 2 phần tốc độ chênh nhau rất xa: **đọc file + ghi DB ≈ 11 giây**, **gọi API ≈ vài phút → vài giờ**. Nên tách làm 2 giai đoạn, giống kho hàng:

```
Xe tải tới  ──▶  [GIAI ĐOẠN 1: dỡ hàng vào kho]  ──▶  [GIAI ĐOẠN 2: kiểm định từng thùng]
(file CSV)        INGEST — Phase 2                    PROCESS — Phase 3
                  nhanh (~11 s / 1 triệu dòng)         chậm, phụ thuộc API
```

**Ingest = đưa toàn bộ file CSV vào bảng staging `import_row` nhanh nhất có thể**, mỗi dòng hợp lệ mang trạng thái `NEW`. Khi dữ liệu đã ở trong DB:
- **Không phụ thuộc file nữa** — file chỉ nằm trên đĩa 1 pod, DB thì pod nào cũng đọc được.
- **Làm tiếp được khi có sự cố** — pod chết thì xử lý tiếp các dòng còn `NEW`.
- **Chia việc cho nhiều pod** — Phase 3 các worker lấy dòng bằng `SKIP LOCKED`, không trùng nhau.
- **Dòng sai bị chặn sớm** — ghi `FAILED` kèm lý do ngay, không tốn lượt gọi API.

### Luồng tổng thể

```
 Client                    ImportService                                         MySQL
   │ POST /imports (file)     │
   ├─────────────────────────▶│ ① Tạo job (QUEUED), lưu file ra đĩa
   │◀──── 202 {jobId:1} ──────│    trả lời ngay, không bắt client chờ
   │                          │
   │                          │ ② Đưa job vào hàng đợi (tối đa max-concurrent-jobs file cùng lúc)
   │                          │
   │                          │ ③ Thread "ingest-job" = READER (1 thread)
   │                          │    đọc từng dòng → kiểm tra → gom thành chunk 1000 dòng
   │                          │           │
   │                          │           ▼  ④ Semaphore: tối đa writers + queue-capacity chunk
   │                          │    ┌──────────────────────────────────┐
   │                          │    │ writer-0  writer-1 ... writer-5  │ ⑤ 6 thread ghi song song
   │                          │    └──────────────┬───────────────────┘
   │                          │                   │ batch insert 1000 dòng/lần ─────▶ import_row
   │                          │                   │                                  (NEW / FAILED)
   │                          │ ⑥ Đọc hết + writer ghi xong → job = PROCESSING, xoá file
   │ GET /imports/1           │
   ├─────────────────────────▶│ ──── đọc import_job ────────────────────────────────▶ import_job
```

Vòng đời job:

```
QUEUED ──▶ INGESTING ──▶ PROCESSING (chờ Phase 3)
                  │
                  └──▶ FAILED (header sai, file hỏng, DB lỗi, kẹt quá stale-after)
```

### Từng bước trong code

| Bước | Ở đâu | Làm gì | Vì sao |
|---|---|---|---|
| ① Nhận file | `ImportJobController.upload` | Tạo `import_job` (QUEUED) → lưu file `<jobId>.csv` vào `import.storage-dir` → trả **202** + `Location: /imports/{id}` | Đọc 1 triệu dòng mất vài chục giây; giữ HTTP chờ dễ timeout và client ngắt thì không biết job tới đâu |
| ② Hàng đợi job | `IngestService.submit` | Thread pool `max-concurrent-jobs` thread; job khác chờ ở QUEUED | Nhiều file cùng lúc không tranh nhau MySQL |
| ③ Reader | `IngestService.ingest` | Mở file UTF-8, bỏ BOM, **kiểm tra header**, đọc **streaming** từng bản ghi bằng Apache Commons CSV, gọi `CustomerCsv.toStagedRow`, gom `chunk-size` dòng | Không nạp cả file vào RAM; Commons CSV hiểu đúng `"12 Nguyen Trai, Q1"` là 1 cột |
| Kiểm tra dòng | `CustomerCsv.toStagedRow` | Cắt khoảng trắng, ô trống → null; đủ 15 cột; bắt buộc `external_id`, `full_name`, `email`; email / ngày `yyyy-MM-dd` / thu nhập hợp lệ; quá dài → cắt + báo lỗi | Dòng sai **không làm hỏng cả file**: vẫn lưu, trạng thái FAILED + lý do |
| ④ Backpressure | `IngestService.submitChunk` | `Semaphore(writers + queue-capacity)`: giao chunk phải lấy 1 "thẻ", hết thẻ thì reader đứng chờ | Reader nhanh hơn writer; không chặn thì reader đọc hết file vào RAM |
| ⑤ Writer | `ImportRowRepository.insertChunk` | 1 chunk = 1 transaction, `jdbc.batchUpdate` + `rewriteBatchedStatements=true` → driver gộp thành `INSERT ... VALUES (...),(...),...` | Gửi 1000 câu riêng = 1000 vòng đi-về mạng; gộp lại nhanh ~8 lần (xem số đo) |
| ⑥ Kết thúc | `IngestService.run` | Chờ writer xong → ghi `total_rows`, `invalid_rows`, job = PROCESSING (hết dòng hợp lệ thì COMPLETED / COMPLETED_WITH_ERRORS) → xoá file | Dữ liệu đã an toàn trong DB |

Minh hoạ ④ — quầy gửi đồ có `writers + queue-capacity` = 16 thẻ:

```
Reader muốn giao 1 chunk ──▶ lấy 1 thẻ (acquire)
                              ├─ còn thẻ  → giao cho writer, đọc tiếp
                              └─ hết thẻ  → ĐỨNG CHỜ, không đọc thêm dòng nào
Writer ghi xong 1 chunk  ──▶ trả thẻ (release) → reader đi tiếp
```

→ Trong RAM luôn ≤ 16 chunk × 1000 = **16.000 dòng**, dù file 1 triệu hay 10 triệu dòng. **Số thread và RAM cố định; số dòng chỉ làm job chạy lâu hơn.**

### Khi có lỗi

| Tình huống | Xử lý |
|---|---|
| Header sai / file không phải UTF-8 / CSV hỏng (thiếu `"` đóng) | Job FAILED + lý do |
| 1 writer ghi lỗi (vd DB sập) | Ghi nhận lỗi đầu tiên → writer khác bỏ qua chunk còn lại, reader ngừng đọc → job FAILED |
| Đã FAILED giữa chừng | Xoá dữ liệu dở trong `import_row` (theo đợt 10.000 dòng) để Phase 3 không xử lý nửa file; **giữ file gốc** để điều tra |
| Pod chết giữa chừng | Job kẹt INGESTING → `StaleIngestSweeper` (mỗi phút) thấy quá `stale-after` thì FAILED + xoá dữ liệu dở |

### Vì sao ở đây KHÔNG dùng virtual thread

| | Reader | Writer |
|---|---|---|
| Thời gian dành cho | Parse CSV → **tốn CPU** | Chờ MySQL, nhưng **bị giới hạn bởi DB** |
| Virtual thread giúp? | ❌ không có chỗ chờ I/O | ❌ 1000 virtual thread vẫn tranh 16 connection |

Virtual thread chỉ có ích ở Phase 3 (gọi API: gần như toàn bộ thời gian là chờ mạng).

---

## Thông số ingest: chọn thế nào, dựa trên kiến thức gì

```properties
import.ingest.writers=6
import.ingest.chunk-size=1000
import.ingest.queue-capacity=10
import.ingest.max-concurrent-jobs=1
import.ingest.stale-after=10m
```

Mỗi con số đến từ một trong ba nguồn sau. Cần phân biệt rõ vì mức tin cậy khác nhau:

| Thông số | Nguồn | Mức tin cậy |
|---|---|---|
| `writers=6` | Lý thuyết cho biết **hình dạng** đường cong; **con số 6 lấy từ đo** | Cao, nhưng chỉ đúng cho máy đã đo |
| `chunk-size=1000` | Lý thuyết chi phí cố định + thông lệ ngành + bạn đề xuất | Trung bình, **chưa đo** |
| `queue-capacity=10` | Lý thuyết producer–consumer / backpressure | Trung bình, **chưa đo** |
| `max-concurrent-jobs=1` | Suy ra từ số đo `writers` + lý thuyết hàng đợi | Trung bình, chưa đo trực tiếp |
| `stale-after=10m` | Nguyên tắc chọn timeout cho việc phát hiện lỗi | Không liên quan tốc độ; **có điểm yếu** (xem dưới) |

### 1. `writers=6`: thêm thread chỉ nhanh hơn cho tới khi chạm nút cổ chai

**Kiến thức dùng:** định luật Amdahl / Universal Scalability Law, và cách tính kích thước connection pool của HikariCP.

- Một writer làm việc theo vòng lặp: Java chuẩn bị batch → gửi qua mạng → MySQL ghi → MySQL commit (ghi redo log xuống đĩa) → trả kết quả về. Trong phần lớn vòng lặp đó, **MySQL đang rảnh** vì chờ Java, chờ mạng hoặc chờ đĩa. Thêm writer giúp **lấp các khoảng chờ này**: writer A đang chờ commit thì writer B gửi batch.
- Khi MySQL đã bận liên tục (CPU, ổ đĩa, lock của index), thêm writer không nhanh hơn nữa, thậm chí có thể chậm đi vì tranh nhau. Đây là nội dung của định luật Amdahl và USL: **throughput tăng rồi đi ngang (hoặc giảm)**. Lý thuyết cho biết đường cong có hình như vậy, nhưng **không cho biết điểm gãy nằm ở đâu**. Muốn biết thì phải đo.
- HikariCP có bài "About Pool Sizing" khuyên pool khoảng `số CPU × 2 + số ổ đĩa`. Máy 8 CPU cho ra khoảng 17, nên pool của mình là 16. Mức concurrency đó phải chia cho cả writer (Phase 2) lẫn worker (Phase 3). Đây là lý do con số ước tính ban đầu là "2–4".
- **Đo thật** (bảng "Số đo" bên dưới): 1 writer 31 giây → 3 writer 11,4 giây → 6 writer 8,3 giây → 8 writer 8,0 giây → 12 writer 8,2 giây. Từ 6 trở lên chỉ còn lệch vài %, nằm trong mức nhiễu, nên chọn 6. Con số ước tính "2–4" ban đầu đã sai.

### 2. `chunk-size=1000`: chia đều chi phí cố định, nhưng không để transaction quá lớn

**Kiến thức dùng:** mô hình chi phí `chi phí mỗi dòng = chi phí cố định mỗi batch / N + chi phí biến đổi mỗi dòng`.

- Chi phí cố định mỗi batch gồm 1 vòng đi về qua mạng, 1 lần commit (fsync redo log) và 1 lần parse câu lệnh. Batch càng lớn thì phần chi phí cố định chia cho mỗi dòng càng nhỏ.
- Lợi ích **giảm dần**. Ví dụ với chi phí cố định 5 ms: N=10 thì mỗi dòng gánh 0,5 ms, N=100 còn 0,05 ms, N=1000 còn 0,005 ms, N=5000 còn 0,001 ms. Từ 1000 lên 5000 chỉ tiết kiệm 0,004 ms mỗi dòng, trong khi chi phí biến đổi (ghi dữ liệu, cập nhật index) không đổi.
- Batch quá lớn thì có hại:
  - transaction dài hơn nên giữ lock lâu hơn và undo log lớn hơn;
  - khi lỗi phải làm lại nhiều hơn;
  - tốn RAM hơn, vì RAM = `chunk × (writers + queue)`;
  - câu lệnh gộp bị chạm giới hạn `max_allowed_packet` của MySQL (mặc định 64MB). 1000 dòng × khoảng 300 byte = 300KB, còn xa giới hạn.
- Thông lệ ngành: Spring Batch thường dùng commit interval 100–1000, còn các hướng dẫn bulk insert JDBC/MySQL thường nằm trong khoảng 500–5000. **1000 nằm giữa khoảng thông lệ**, và cũng là con số bạn đề xuất ("batch 1000 record 1 lượt").

### 3. `queue-capacity=10`: hàng đợi chỉ cần đủ để hấp thụ dao động

**Kiến thức dùng:** bài toán producer–consumer với buffer có giới hạn, định luật Little, và khái niệm backpressure.

- Ở đây reader (producer) nhanh hơn writer (consumer). Khi producer nhanh hơn **về trung bình**, hàng đợi **luôn đầy dù lớn cỡ nào**. Tốc độ tổng thể do bên chậm nhất quyết định (writer, thực chất là MySQL), nên **hàng đợi to hơn không làm nhanh hơn, chỉ tốn thêm RAM**.
- Nhưng hàng đợi bằng 0 cũng không ổn. Khi 1 writer ghi xong, nó phải **có ngay chunk tiếp theo** để làm, không phải đứng chờ reader parse xong 1000 dòng mới. Buffer tồn tại để hấp thụ **dao động**: có lúc MySQL fsync chậm, có lúc JVM dừng để GC.
- Chọn 10 (khoảng 1,5 lần số writer) để mỗi writer luôn có sẵn 1–2 chunk chờ. RAM tối đa = (6 + 10) × 1000 dòng × khoảng 1KB/dòng (15 chuỗi Java) ≈ **16MB**, rất nhỏ so với heap.

### 4. `max-concurrent-jobs=1`: tài nguyên dùng chung không tăng thêm thì chạy lần lượt tốt hơn chạy song song

**Kiến thức dùng:** lý thuyết hàng đợi (xếp lịch trên một tài nguyên dùng chung), suy ra từ số đo của `writers`.

- 1 job với 6 writer đã làm MySQL bão hoà (12 writer không nhanh hơn). Nếu cho 2 job chạy cùng lúc thì có 12 writer, **tổng tốc độ vẫn vậy**, chỉ là chia đôi cho mỗi job.
- So sánh 2 file, mỗi file cần 11 giây nếu chạy một mình:

  | Cách chạy | File A xong lúc | File B xong lúc | Thời gian chờ trung bình |
  |---|---|---|---|
  | Lần lượt (1 job) | 11 s | 22 s | **16,5 s** |
  | Song song (2 job) | ~22 s | ~22 s | 22 s |

  Chạy lần lượt thì **không ai phải chờ lâu hơn**, và người đến trước xong sớm hơn.
- Ngoài ra còn phải chừa sức của MySQL cho Phase 3 (worker ghi bảng `customer`).
- Chưa đo trực tiếp. Nếu MySQL mạnh hơn (nhiều CPU, SSD tốt), điểm bão hoà tăng thì có thể tăng giá trị này.

### 5. `stale-after=10m`: timeout phát hiện lỗi, không liên quan tốc độ

**Kiến thức dùng:** nguyên tắc chọn timeout cho việc phát hiện lỗi (giống `failureThreshold × periodSeconds` của liveness probe trong K8s, hay heartbeat timeout trong hệ phân tán).

- Timeout quá ngắn thì **báo nhầm**: đánh FAILED một job vẫn đang chạy bình thường, gây hậu quả nặng.
- Timeout quá dài thì **phát hiện chậm**: job thật sự đã chết nhưng người dùng phải chờ lâu mới biết, hậu quả nhẹ (chỉ là chờ thêm).
- Vì báo nhầm nguy hiểm hơn, timeout nên **lớn hơn nhiều lần thời gian chạy bình thường**. 1 triệu dòng mất 11–35 giây, 10 phút cho biên an toàn khoảng 17–50 lần.
- ⚠️ **Điểm yếu:** hiện tại sweeper tính từ `started_at` (lúc job **bắt đầu**), không phải lúc job **có tiến triển gần nhất**. Một file rất lớn trên máy chậm, ví dụ 20 triệu dòng ở 28.000 dòng/giây ≈ 12 phút, sẽ bị đánh FAILED nhầm. Cách đúng là **heartbeat**: mỗi chunk ghi xong thì cập nhật một cột `heartbeat_at`, và sweeper xét "không có heartbeat trong X phút". Nên sửa ở Phase 4.

### Nếu không cấu hình gì thì giá trị là bao nhiêu?

| Trường hợp | Trước khi sửa | Sau khi sửa (2026-09-28) |
|---|---|---|
| Không có dòng `import.ingest.*` nào | `ingest = null` → **app lỗi khi khởi động** | Dùng giá trị trong code (`@DefaultValue`): 6 / 1000 / 10 / 1 / 10m |
| Có 1 vài dòng, thiếu dòng khác | Dòng thiếu nhận **0** → `writers=0` → app lỗi | Dòng thiếu nhận giá trị mặc định ở trên |

Kiểm chứng: `ImportPropertiesTest`. Các giá trị mặc định **của thư viện** liên quan cũng đáng biết:

| Thư viện | Mặc định | Ảnh hưởng |
|---|---|---|
| MySQL Connector/J `rewriteBatchedStatements` | `false` | Batch 1000 câu vẫn gửi **từng câu một** → 1 triệu dòng mất 135 s thay vì 17 s |
| HikariCP `maximum-pool-size` | 10 | Không đủ cho 6 writer + 8 worker → đặt 16 |
| Spring `multipart.max-file-size` | 1MB | File 147MB bị từ chối → đặt 500MB |

### Cách tính "nhanh hơn bao nhiêu phần trăm"

Gọi `T_cũ` và `T_mới` là thời gian chạy (nên lấy **trung vị** của nhiều lần đo để bớt nhiễu):

| Muốn nói | Công thức | Ví dụ: 1 writer (31,4 s) → 6 writer (8,3 s) |
|---|---|---|
| Giảm bao nhiêu % thời gian | `(T_cũ − T_mới) / T_cũ × 100` | (31,4 − 8,3) / 31,4 = **giảm 74%** |
| Nhanh gấp bao nhiêu lần | `T_cũ / T_mới` | 31,4 / 8,3 = **gấp 3,8 lần** |
| Throughput tăng bao nhiêu % | `(T_cũ / T_mới − 1) × 100` | (3,8 − 1) × 100 = **tăng 278%** |

⚠️ "Giảm 74% thời gian" và "tăng 278% throughput" là **cùng một kết quả** diễn đạt theo hai cách. Khi đọc báo cáo, luôn phải hỏi phần trăm đó là phần trăm của cái gì.

Ví dụ khác: bật `rewriteBatchedStatements`, 135 s → 17 s: giảm 87% thời gian, nhanh gấp 7,9 lần.

Muốn so sánh cho công bằng thì: **chỉ đổi 1 thông số mỗi lần**, dùng cùng file và cùng máy, chạy nhiều lần lấy trung vị, và bắt đầu mỗi lần từ trạng thái giống nhau (bảng rỗng). `IngestBenchmarkTest` làm đúng như vậy.

### Số đo (2026-09-28)

Môi trường: file 1 triệu dòng (147 MB, 1000 dòng sai), MySQL 8.4 trong Docker Desktop (Testcontainers, cấu hình mặc định), Mac arm64 8 CPU. Chunk 1000, queue 10. Mỗi cấu hình chạy 3 lần, `TRUNCATE import_row` trước mỗi lần, lấy trung vị.

| Writer | Trung vị | Dòng/giây | 3 lần đo (ms) | So với 1 writer |
|---|---|---|---|---|
| 1 | 31,4 s | ~31.900 | 31374, 32980, 27882 | — |
| 2 | 15,6 s | ~64.200 | 15374, 15912, 15575 | nhanh gấp 2,0 |
| 3 | 11,4 s | ~87.900 | 11789, 10975, 11375 | gấp 2,8 |
| 4 | 9,5 s | ~105.300 | 9498, 9167, 10209 | gấp 3,3 |
| **6 (mặc định)** | **8,3 s** | **~120.400** | 8561, 8303, 8148 | **gấp 3,8** |
| 8 | 8,0 s | ~124.600 | 8026, 7835, 8053 | gấp 3,9 |
| 12 | 8,2 s | ~121.600 | 8225, 8354, 8081 | gấp 3,8 |

Đọc bảng:
- 1 → 2 writer nhanh gấp đôi: MySQL còn rảnh rất nhiều.
- Mỗi lần thêm writer, phần lợi thêm được càng nhỏ, đúng như Amdahl/USL dự đoán.
- 6 → 8 chỉ nhanh hơn khoảng 3%, còn 12 chậm hơn 8. Mức chênh này nằm trong độ dao động giữa các lần đo (lần 1 writer lệch tới 5 s), nên **coi là đã bão hoà từ khoảng 6**.
- Các số này nhanh hơn lần đo ngày 27 (6 writer: 11 s) vì lần này bảng được làm rỗng trước mỗi lần. Lần trước, lần đo thứ hai ghi vào bảng đã có sẵn 1 triệu dòng nên index lớn hơn và chậm hơn. Đây là ví dụ cho thấy **điều kiện ban đầu phải giống nhau** thì các lần đo mới so sánh được.

Chưa đo: `chunk-size` và `queue-capacity` (benchmark đã dừng trước khi tới phần này). Muốn đo lại:

```bash
python3 scripts/gen-customers-csv.py 1000000 /tmp/customers-1m.csv --invalid-every 1000
cd ImportService
BENCH_FILE=/tmp/customers-1m.csv ./gradlew test --tests '*IngestBenchmarkTest' --rerun -i | grep "BENCH |"
```

Benchmark mất khoảng 15 phút và **không chạy** trong `./gradlew test` thường (chỉ chạy khi có biến `BENCH_FILE`).
