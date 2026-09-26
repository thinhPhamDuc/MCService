# Production thực tế vận hành thế nào?

> Mọi thứ đã học (Dockerfile, Compose, ELK, K8s) là **các mảnh ghép** của production.
> File này giải thích các mảnh **nối với nhau thế nào** và **ai vận hành cái gì**.
> Thiết kế hạ tầng cụ thể bằng Terraform: xem [`production-design.md`](production-design.md).

---

## 1. Bức tranh tổng thể: code đi từ máy dev ra production thế nào

```
 Dev                CI (tự động)                       Registry            CD (tự động)              Production
┌──────┐  push   ┌──────────────────────────┐  push   ┌──────────┐  deploy  ┌──────────┐   ┌─────────────────────────────────┐
│ Code │ ──────▶ │ GitHub Actions / GitLab  │ ──────▶ │ AWS ECR  │ ───────▶ │ ArgoCD / │ ─▶│ K8s cluster (EKS/GKE)           │
│ + PR │         │ 1. build + chạy test     │  image  │ Docker   │          │ Helm     │   │  ├ Ingress + Load Balancer + TLS│
└──────┘         │ 2. quét lỗ hổng bảo mật  │         │ Hub...   │          └──────────┘   │  ├ Pods (3 service × N replica)  │
    ▲            │ 3. docker build + tag    │         └──────────┘                         │  └ Secrets lấy từ Vault/AWS SM   │
    │            └──────────────────────────┘                                              └──────────┬──────────────────────┘
    │                                                                                                 │
    │                                                         ┌───────────────────────────────────────┼──────────────┐
    │                                                         ▼                                       ▼              ▼
    │                                                  Managed DB (RDS)                 Logs / Metrics / Traces   Alerts
    │                                                  backup tự động                   Kibana, Grafana, Jaeger    PagerDuty, Slack
    └────────────────────────── on-call engineer nhận cảnh báo, điều tra, sửa ────────────────────────────────────────┘
```

**Nguyên tắc số 1: không ai gõ lệnh tay lên production.** Không `docker build` trên laptop, không `kubectl apply` từ máy cá nhân.
Mọi thay đổi đều đi qua **Git, rồi đến pipeline tự động**, vì:

- Có lịch sử: ai đổi gì, lúc nào, vì sao (xem ở PR).
- Có review: người khác duyệt rồi mới được merge.
- Làm lại được: rollback bằng cách revert commit.

---

## 2. Đối chiếu những gì đã học với production

| Đã làm (local) | Production thực tế | Vì sao khác |
|---|---|---|
| `docker build` trên máy | **CI** build tự động mỗi khi merge | Máy ai build cũng ra cùng kết quả, có test chặn trước |
| Tag `v1`, `v2` tự đặt | Tag = **git commit SHA** (`orderservice:a3f9c1e`) | Từ image bất kỳ truy ngược được đúng dòng code |
| Image nằm trong máy | **Registry** (ECR, GCR, Harbor) | 50 node đều phải kéo được image |
| `kubectl apply -f k8s/` | **GitOps**: ArgoCD tự đồng bộ repo manifest vào cluster | Cluster luôn khớp với Git, có người sửa tay thì ArgoCD tự sửa lại |
| 6 file YAML viết tay | **Helm chart / Kustomize**: 1 template, mỗi môi trường 1 file values | Không copy-paste 3 lần |
| `port-forward` | **Ingress + Load Balancer + DNS + TLS** (`api.shop.com`) | Khách truy cập qua domain và HTTPS |
| Postgres chạy trong K8s | **Managed DB** (AWS RDS, Cloud SQL) | Tự backup, tự failover, tự vá bảo mật. DB là thứ không được phép mất |
| Secret viết trong YAML | **Vault / AWS Secrets Manager**, đưa vào qua External Secrets | Mật khẩu không bao giờ nằm trong Git |
| `ddl-auto=update` | **Flyway / Liquibase** migration | Mọi thay đổi schema phải được kiểm soát và review |
| ELK tắt security | ELK có xác thực, hoặc dịch vụ managed (Elastic Cloud, Datadog) | |
| Chỉ có log | **3 trụ quan sát**: Logs + **Metrics** (Prometheus/Grafana) + **Traces** (OpenTelemetry/Jaeger) | Log trả lời "chuyện gì xảy ra". Metrics trả lời "có khoẻ không". Trace trả lời "chậm ở đâu" |
| Tự nhìn Kibana | **Alert tự động**: tỉ lệ lỗi > 1% trong 5 phút thì gọi điện người trực | Không ai ngồi nhìn dashboard 24/7 |
| 1 môi trường | **dev → staging → production** | Staging giống production để thử trước khi lên thật |

---

## 3. Một ngày làm việc thực tế

### 🟢 Kịch bản 1: Deploy tính năng mới

```
09:00  Dev tạo branch, code, push, rồi mở Pull Request
09:05  CI tự chạy: build, unit test, integration test, quét bảo mật  ✅
10:00  Đồng nghiệp review, approve, merge vào main
10:05  CI build image orderservice:a3f9c1e và push lên ECR
10:06  CI tự sửa tag image trong repo manifest (môi trường staging)
10:07  ArgoCD phát hiện thay đổi, rolling update trên staging     ← TH 5.6
10:30  QA test trên staging  ✅
14:00  Tạo PR "promote lên production", Tech lead approve
14:05  ArgoCD deploy production theo kiểu CANARY:
         5% traffic vào bản mới → theo dõi tỉ lệ lỗi 15 phút
         ✅ ổn → 50% → 100%
         ❌ lỗi tăng → tự động rollback                           ← TH 5.7
```

Nhiều công ty **cấm deploy chiều thứ Sáu** và trước ngày lễ, vì deploy lỗi thì không có ai trực để sửa.

### 🔴 Kịch bản 2: Sự cố lúc 2 giờ sáng

```
02:00  Alert: "OrderService error rate 15% (ngưỡng 1%)"
        → gọi điện cho kỹ sư đang on-call (trực luân phiên theo tuần)
02:05  Mở Grafana: lỗi bắt đầu từ 01:55. Latency của PaymentService tăng vọt
02:08  Mở Kibana: log.level:ERROR, service.name:OrderService → toàn bộ là timeout gọi Payment
        (TH 4.3 "thủ phạm hay nạn nhân")
02:10  Mở trace: Payment chậm vì query DB mất 8 giây
02:12  Xem lịch sử deploy: PaymentService vừa deploy lúc 01:50
02:13  ƯU TIÊN KHÔI PHỤC TRƯỚC, TÌM NGUYÊN NHÂN SAU: rollback PaymentService
02:15  Tỉ lệ lỗi về 0%. Báo lên kênh #incident
       Sáng hôm sau: tìm nguyên nhân gốc (thiếu index DB), viết Postmortem
```

**Postmortem** là tài liệu ghi lại sự cố: chuyện gì xảy ra, ảnh hưởng bao nhiêu khách, nguyên nhân gốc, và **làm gì để không lặp lại**.
Văn hoá tốt là **không đổ lỗi cho cá nhân** (blameless). Câu hỏi là "*hệ thống* sai ở đâu mà cho lỗi lọt qua?", chứ không phải "*ai* làm sai".

---

## 4. Ai làm gì trong team?

| Vai trò | Phụ trách |
|---|---|
| **Developer** (Backend) | Code, test, Dockerfile, cấu hình qua env, viết log tốt, health endpoint, và **tự trực on-call cho service của mình** ("you build it, you run it") |
| **DevOps / Platform Engineer** | Dựng cluster, CI/CD pipeline, Helm template chung, registry, network, bảo mật, **Terraform** |
| **SRE** (Site Reliability) | Độ ổn định: SLO, alert, capacity, chaos testing, dẫn dắt xử lý sự cố |
| **DBA** (công ty lớn) | Database, backup, tối ưu query |

Công ty nhỏ thì thường **một người kiêm hết**. Backend Developer hiện nay cũng được kỳ vọng hiểu được khoảng 70% những thứ này.

---

## 5. ⚠️ Không phải công ty nào cũng dùng K8s

| Quy mô | Thường dùng | Giống bài nào |
|---|---|---|
| Startup nhỏ, vài service | **1 đến 2 VM + Docker Compose**, CI deploy qua SSH | Bài 3 |
| Vừa, không muốn tự quản cluster | **AWS ECS / Google Cloud Run / Fly.io** | Giữa bài 3 và bài 5 |
| Lớn, nhiều team, nhiều service | **Kubernetes (EKS/GKE)** + GitOps | Bài 5 |

K8s mạnh nhưng **tốn công vận hành**. Chọn công nghệ **vừa với quy mô** cũng là một kỹ năng quan trọng.

---

## 6. Code demo còn thiếu gì để lên production?

Code được viết **tối giản cho mục đích học**. Lên production thật cần sửa:

| Vấn đề | Production cần |
|---|---|
| Mật khẩu lưu plain text | Hash bằng BCrypt |
| Token là UUID lưu DB, mỗi request đều gọi UserService | JWT: OrderService tự xác minh, không cần gọi UserService mỗi lần |
| `/admin/chaos` không cần đăng nhập | Xoá khỏi production, hoặc bảo vệ bằng auth |
| `ddl-auto=update` | Flyway migration |
| **Order báo lỗi mà tiền vẫn bị trừ** (TH7 bài 3) | **Idempotency key** + **Saga / Outbox pattern** + message queue (Kafka/RabbitMQ) |
| Gọi service không retry | Retry + Circuit breaker (Resilience4j) |
| `requestId` tự làm | OpenTelemetry tracing |

"Order báo lỗi mà tiền vẫn bị trừ" là **bài toán khó nhất của microservices**.
Đó cũng là lý do người ta hay khuyên *"đừng tách microservices nếu chưa thật sự cần"*.

---

## 7. Lộ trình tiếp theo (sau bài 5)

```
Bài 6: Registry + CI       → GitHub Actions tự build image, push lên Docker Hub / GHCR
Bài 7: Helm                → gộp 6 file YAML thành 1 chart, deploy dev/prod bằng 2 file values
Bài 8: Ingress             → truy cập qua domain thay vì port-forward
Bài 9: Metrics + Alert     → Prometheus + Grafana, cảnh báo khi tỉ lệ lỗi tăng
Bài 10: Cloud              → đưa lên EKS thật bằng Terraform (xem production-design.md)
```

Song song, phía code có thể làm **Saga/Outbox** để giải bài toán "khách bị trừ tiền mà đơn báo lỗi".
