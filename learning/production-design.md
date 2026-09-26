# Thiết kế hạ tầng Production cho MCService (AWS + Terraform)

> Code: [`infra/terraform/`](../infra/terraform/)
> Bối cảnh "production vận hành thế nào": [`production-overview.md`](production-overview.md)
>
> ⚠️ **Đây là thiết kế tham khảo để học.** Code đã qua `terraform fmt` + `terraform validate` (đúng cú pháp, đúng tham số của module),
> nhưng **chưa từng `plan`/`apply` trên AWS thật**. Nếu apply thật sẽ **tốn tiền mỗi giờ** (xem mục 9).

---

## 1. Ba lớp trách nhiệm: ai quản lý cái gì?

Câu hỏi đầu tiên khi thiết kế production **không phải** "dùng công cụ gì", mà là **"thứ gì thay đổi bao lâu một lần, và ai được phép thay đổi nó"**.

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│  Lớp 1: HẠ TẦNG           Terraform          Đổi: vài lần / tháng   Ai: DevOps          │
│  VPC, EKS, RDS, ECR, IAM, add-on của cluster                                             │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Lớp 2: ỨNG DỤNG TRÊN K8S  GitOps (ArgoCD)   Đổi: nhiều lần / ngày  Ai: Dev (qua PR)    │
│  Deployment, Service, Ingress, HPA, ExternalSecret của từng service                      │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  Lớp 3: IMAGE              CI (GitHub Actions) Đổi: mỗi lần merge   Ai: tự động         │
│  Build → test → push image lên ECR với tag = git SHA                                     │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**Vì sao không để Terraform quản lý luôn Deployment của app?**
Vì mỗi lần dev đổi 1 dòng code, Terraform phải `plan` lại cả VPC, EKS, RDS. Việc đó vừa chậm, vừa **nguy hiểm**: một sai sót nhỏ trong PR của app có thể đụng vào database.
Tách lớp nghĩa là **vùng ảnh hưởng (blast radius)** của mỗi thay đổi nhỏ nhất có thể.

---

## 2. Kiến trúc tổng thể

```
                                   Internet (khách hàng)
                                          │  HTTPS api.shop.com
┌─ Region ap-southeast-1 (Singapore) ─────┼────────────────────────────────────────────────────────────┐
│ ┌─ VPC 10.0.0.0/16 ─────────────────────┼──────────────────────────────────────────────────────────┐ │
│ │                                       ▼                                                          │ │
│ │  PUBLIC      ┌──────────────── ALB (Application Load Balancer) ────────────────┐                 │ │
│ │  /24 × 3 AZ  │  /auth/*  → userservice        /orders/*  → orderservice        │   NAT × 3       │ │
│ │              └───────────────────────────────────────────────────────────────┬─┘   (đi ra ngoài) │ │
│ │ ─────────────────────────────────────────────────────────────────────────────┼────────────▲──── │ │
│ │  PRIVATE     EKS (Auto Mode) – node và pod trải đều 3 AZ                     ▼            │      │ │
│ │  /20 × 3 AZ  ┌────────────┐   ┌────────────┐   ┌──────────────┐   ┌──────────────────────┐ │      │ │
│ │              │ userservice│   │orderservice│──▶│paymentservice│   │ argocd, ESO,         │─┘      │ │
│ │              │  × 2..N    │◀──│  × 2..N    │   │  × 2..N      │   │ cloudwatch-agent,    │ kéo image,
│ │              └─────┬──────┘   └─────┬──────┘   └──────┬───────┘   │ metrics-server       │ gọi AWS API
│ │                    │                │  (không có Ingress → không ai ngoài gọi được)       │        │ │
│ │ ───────────────────┼────────────────┼─────────────────┼──────────────────────────────────────── │ │
│ │  DATABASE          ▼                ▼                 ▼                                          │ │
│ │  /24 × 3 AZ   RDS user_db      RDS order_db     RDS payment_db    (Multi-AZ, không ra internet)  │ │
│ └──────────────────────────────────────────────────────────────────────────────────────────────────┘ │
│   Dịch vụ AWS: ECR (image) · Secrets Manager (mật khẩu DB) · CloudWatch (log + metrics) · S3 (tfstate)│
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Cấu trúc code: vì sao lại chia 4 stack?

```
infra/terraform/
├── bootstrap/          Stack 0: S3 bucket chứa state          chạy 1 lần / account, state để local
├── global/             Stack 1: ECR + GitHub OIDC role        chạy 1 lần, dùng chung mọi môi trường
├── infrastructure/     Stack 2: VPC + EKS + RDS + IAM          mỗi môi trường 1 state
│   └── envs/           staging.tfvars, prod.tfvars, *.s3.tfbackend
├── platform/           Stack 3: add-on Helm (ArgoCD, ESO...)   mỗi môi trường 1 state
│   └── envs/
└── modules/postgres/   Module tái sử dụng: 1 RDS cho 1 service
```

| Quyết định | Lý do |
|---|---|
| **Tách stack theo vòng đời** | `bootstrap` gần như không bao giờ đổi. `global` hiếm khi đổi. `infrastructure` vài lần mỗi tháng. `platform` đổi khi nâng cấp add-on. Mỗi `apply` chỉ đụng tới phần cần đổi, lỗi cũng chỉ ảnh hưởng trong phạm vi đó |
| **`platform` tách khỏi `infrastructure`** | Provider `helm` cần **cluster đã tồn tại** để kết nối. Gộp chung thì lần apply đầu (chưa có cluster) sẽ lỗi. Đây là một lỗi kinh điển khi mới làm Terraform + K8s |
| **`bootstrap` để state local** | Bài toán con gà và quả trứng: chưa có bucket thì không thể lưu state vào bucket |
| **Cùng code, khác `tfvars` + `tfbackend`** | Staging và prod **chạy cùng một đoạn code**, chỉ khác tham số. Nhờ vậy staging mới thật sự là "bản thử" của prod. Nếu mỗi môi trường một bản code riêng thì sớm muộn 2 bản sẽ lệch nhau |
| **Mỗi môi trường 1 file state** | `terraform destroy` nhầm trên staging không thể xoá được prod |
| **Dùng module cộng đồng** (`terraform-aws-modules/vpc`, `/eks`) | Đã được hàng nghìn công ty dùng và kiểm chứng. Tự viết VPC + EKS từ đầu mất vài trăm dòng và dễ sai |
| **Tự viết module `postgres`** | RDS đơn giản, tự viết thì **thấy rõ từng thiết lập**. Module được gọi 3 lần nên 3 DB giống hệt nhau |
| **Ghim version** module, provider, chart | Lần apply nào cũng ra cùng kết quả. Muốn nâng cấp thì sửa version rồi mở PR để review, không để phiên bản tự nhảy |

### State: S3 + native lock

```hcl
backend "s3" {
  bucket       = "mcservice-tfstate-<account-id>"
  key          = "infrastructure/prod.tfstate"
  use_lockfile = true   # khoá bằng file .tflock trên S3 (Terraform ≥ 1.10), không cần DynamoDB nữa
  encrypt      = true
}
```

- **Lock:** 2 người chạy `apply` cùng lúc thì người thứ hai bị chặn. Không có lock, 2 lần apply có thể ghi đè state của nhau, và Terraform sẽ mất dấu tài nguyên đang tồn tại.
- **Versioning:** state bị ghi hỏng vẫn khôi phục được bản trước.
- **Mã hoá:** state chứa endpoint, ARN, và đôi khi cả dữ liệu nhạy cảm.
- **`prevent_destroy`:** mất bucket nghĩa là mất state, và Terraform sẽ "quên" toàn bộ hạ tầng đang chạy.

---

## 4. Phân tích từng quyết định

### 4.1 Region: `ap-southeast-1` (Singapore)
Đây là region gần Việt Nam nhất và có đủ dịch vụ. Độ trễ từ Việt Nam khoảng 30 đến 50ms, trong khi đi Mỹ là khoảng 200ms.

### 4.2 Mạng: VPC 3 tầng × 3 AZ

| Tầng | Chứa | Vào từ internet? | Ra internet? |
|---|---|---|---|
| public | ALB, NAT Gateway | ✅ (chỉ qua ALB) | ✅ |
| private | EKS node, pod | ❌ | ✅ qua NAT (để kéo image, gọi API bên ngoài) |
| database | RDS | ❌ | ❌ |

- **Vì sao 3 AZ?** Mỗi AZ là một trung tâm dữ liệu độc lập. Nếu một AZ mất điện, 2 AZ còn lại vẫn phục vụ được.
- **Vì sao private subnet lại to (/20) như vậy?** Trên EKS, **mỗi pod lấy một IP thật của VPC** (VPC CNI). Nếu subnet quá nhỏ, cluster sẽ hết IP và pod không tạo được nữa. Lỗi này rất khó chẩn đoán khi gặp lần đầu.
- **NAT theo môi trường:**
  - staging dùng `single_nat_gateway = true`: rẻ, nhưng AZ chứa NAT sập thì cả VPC mất đường ra internet.
  - prod dùng `one_nat_gateway_per_az`: mỗi AZ tự đi ra internet qua NAT của nó. NAT Gateway là một trong những khoản **tốn tiền âm thầm** nhất của AWS.
- **Flow log:** ghi lại mọi kết nối mạng. Khi có sự cố bảo mật, đây là nơi trả lời câu hỏi "ai đã kết nối tới đâu".
- **Tag subnet `kubernetes.io/role/elb`:** giúp EKS biết đặt ALB vào subnet nào.

### 4.3 Kubernetes: EKS Auto Mode

| Lựa chọn | Ưu | Nhược |
|---|---|---|
| **EKS Auto Mode** ✅ | AWS tự tạo, scale, vá và thay node. Có sẵn LB controller, CNI, Pod Identity | Phí quản lý cộng thêm vào tiền EC2. Ít tuỳ biến node hơn |
| EKS + Managed Node Group | Tuỳ biến nhiều hơn | Phải tự cài LB controller, Karpenter/autoscaler, tự nâng cấp AMI |
| ECS Fargate | Đơn giản hơn K8s rất nhiều | Không phải K8s, nên kiến thức Bài 5 không dùng lại được |
| Tự dựng K8s trên EC2 | Toàn quyền | Tự lo control plane, etcd, backup... Không đáng |

**Chọn Auto Mode** vì với team nhỏ, **thời gian của kỹ sư đắt hơn tiền phí quản lý**. Chuyện tự vá node hằng tuần là việc không tạo ra giá trị cho sản phẩm.

Một số thiết lập khác:
- **API server:** truy cập được từ internet, nhưng **chỉ từ IP văn phòng/VPN** (`endpoint_public_access_cidrs`).
- **Quyền vào cluster:** dùng **EKS access entry** gắn với IAM role (ví dụ role SSO của team). Không dùng ConfigMap `aws-auth` kiểu cũ, vì sửa sai ConfigMap đó có thể tự khoá mình khỏi cluster.
- **Control plane log** (`api`, `audit`, `authenticator`): trả lời câu hỏi "ai đã xoá deployment lúc 2 giờ sáng?".
- **`deletion_protection` bật cho prod.**

### 4.4 Database: 3 RDS riêng biệt (database-per-service)

| Lựa chọn | Ưu | Nhược |
|---|---|---|
| **3 RDS instance** ✅ | Cô lập hoàn toàn: Payment quá tải hay migration lỗi cũng không ảnh hưởng User/Order. Mỗi DB scale riêng | Tốn khoảng gấp 3 |
| 1 RDS, 3 database bên trong | Rẻ | Một query nặng của Order làm chậm luôn Payment. Khi nâng cấp hay bảo trì thì cả 3 cùng down |
| Postgres trong K8s (như Bài 5) | Rẻ nhất | Tự lo backup, failover, nâng cấp. Mất dữ liệu là thảm hoạ |

Những thiết lập đáng chú ý trong module `postgres`:

| Thiết lập | Vì sao |
|---|---|
| `manage_master_user_password = true` | RDS **tự sinh mật khẩu**, lưu vào Secrets Manager. Mật khẩu **không bao giờ** xuất hiện trong code, trong tfvars hay trong Terraform state |
| `publicly_accessible = false` + SG chỉ mở cho private subnet | Chỉ pod trong VPC mới kết nối được |
| `rds.force_ssl = 1` | Dữ liệu giữa app và DB được mã hoá trên đường truyền |
| `storage_encrypted = true` | Mã hoá dữ liệu lưu trên đĩa. Đây là yêu cầu gần như bắt buộc khi DB chứa dữ liệu thanh toán |
| `multi_az` (prod) | Có bản sao đồng bộ ở AZ khác, AZ chính sập thì **tự failover trong 1 đến 2 phút** |
| `backup_retention_period` (14 ngày cho prod) | **Point-in-time recovery**: khôi phục DB về đúng thời điểm, ví dụ "14:32:05 hôm qua, trước khi chạy lệnh DELETE nhầm" |
| `backup_window` / `maintenance_window` lúc 1 đến 3 giờ sáng giờ VN | Chạy vào lúc ít traffic nhất |
| `deletion_protection` + `final_snapshot` (prod) | Một lệnh `destroy` nhầm cũng không xoá được DB. Nếu xoá thật thì vẫn còn snapshot cuối |
| `max_allocated_storage` | Ổ đĩa tự nới khi sắp đầy. Hết ổ đĩa là DB ngừng ghi, cả hệ thống sập |
| `log_min_duration_statement = 500` | Ghi log query chạy chậm hơn 500ms. Đây chính là manh mối cho kịch bản sự cố 2 giờ sáng trong `production-overview.md` |
| `apply_immediately = false` | Thay đổi cấu hình chờ đến maintenance window, tránh restart DB giữa giờ cao điểm |
| Postgres **16** | **Dev/prod parity**: dùng cùng major version với `postgres:16-alpine` ở local. Bug xuất hiện ở local sẽ giống bug trên prod |

### 4.5 Secrets: Secrets Manager → External Secrets Operator → Pod

```
RDS tự sinh mật khẩu ──▶ AWS Secrets Manager ──(ESO đọc bằng Pod Identity)──▶ K8s Secret ──▶ env của pod
                              ▲ tự xoay vòng mật khẩu                           ▲ ESO tự cập nhật khi mật khẩu đổi
```

So với Bài 5 (Secret viết thẳng trong file YAML, và file đó nằm trong Git):

| Cách | Vấn đề |
|---|---|
| Secret trong YAML, commit Git | Ai đọc được repo là có mật khẩu production. Lịch sử Git thì không bao giờ xoá sạch được |
| Sealed Secrets | Tốt hơn, nhưng đổi mật khẩu vẫn phải mã hoá lại rồi commit |
| **Secrets Manager + ESO** ✅ | Mật khẩu chỉ nằm ở AWS. Git chỉ ghi *"lấy secret tên X"*. Xoay vòng mật khẩu diễn ra tự động |

**Pod Identity** trả lời câu hỏi "pod lấy quyền AWS kiểu gì mà không cầm access key?".
Quy tắc là: pod trong namespace `external-secrets`, dùng service account `external-secrets`, thì được assume role `...-external-secrets`.
Role này **chỉ đọc được đúng 3 secret** của 3 DB (least privilege).

### 4.6 Registry: ECR ở stack `global`

- **Build once, deploy many:** CI build image **một lần duy nhất**, và **chính image đó** chạy trên staging rồi được promote lên prod. Nếu mỗi môi trường tự build lại thì thứ chạy trên prod **không phải** thứ đã test trên staging. Đó là lý do ECR không nằm trong stack theo môi trường.
- **`IMMUTABLE` tag:** tag `a3f9c1e` đã push thì không ghi đè được. Không thể có chuyện "cùng tag mà hôm qua với hôm nay chạy code khác nhau".
- **`scan_on_push`:** tự quét lỗ hổng bảo mật (CVE) trong image.
- **Lifecycle policy:** chỉ giữ 50 image mới nhất, tránh tiền lưu trữ tăng mãi.

### 4.7 CI: GitHub Actions + OIDC (không access key)

```
GitHub Actions ──(token OIDC ngắn hạn: "tôi là repo X, branch main")──▶ AWS STS ──▶ role github-ci ──▶ push ECR
```

- **Không có access key dài hạn** nằm trong GitHub Secrets. Lộ access key là một trong những sự cố bảo mật phổ biến nhất trên AWS.
- Trust policy **chỉ chấp nhận branch `main`** của đúng repo. Một PR từ fork không thể push image.
- **CI không có quyền vào cluster.** Việc deploy do ArgoCD **bên trong cluster** tự *kéo* từ Git (mô hình pull).
  Nếu CI bị hack, kẻ tấn công chỉ push được image, không chạy được lệnh nào trên production.

### 4.8 Observability: CloudWatch Container Insights

| Bài 4 (local) | Production trong thiết kế này |
|---|---|
| App log JSON ra stdout | **Giữ nguyên**, code không đổi gì |
| Filebeat thu log | CloudWatch agent / Fluent Bit (EKS add-on `amazon-cloudwatch-observability`) |
| Elasticsearch lưu | CloudWatch Logs (có `log_retention_days`) |
| Kibana tìm kiếm | CloudWatch Logs Insights: `filter requestId = "trace-1"` |
| (chưa có) | **Metrics** CPU/RAM/restart của pod, và **alarm** |

Vì sao chọn CloudWatch mà không tự dựng ELK trên production?
Tự vận hành Elasticsearch (cluster, shard, disk, nâng cấp) là **cả một công việc riêng**. Với team nhỏ, dịch vụ managed rẻ hơn tính theo công sức.
Công ty lớn thì hay dùng Elastic Cloud, Datadog, hoặc Grafana stack (Loki + Prometheus + Tempo).

> 💡 Nhờ app **chỉ in log ra stdout** (nguyên tắc ở Bài 4), đổi từ ELK sang CloudWatch **không cần sửa một dòng code nào**.

### 4.9 Khác biệt giữa các môi trường

| | staging | prod | Vì sao khác |
|---|---|---|---|
| NAT Gateway | 1 | 3 (mỗi AZ 1) | Prod cần chịu được lỗi AZ |
| RDS | `db.t4g.micro`, 1 AZ | `db.t4g.medium`, Multi-AZ | Tiền |
| Backup | 3 ngày | 14 ngày | |
| Deletion protection | tắt | bật | Staging cần tạo và xoá thoải mái |
| Log retention | 14 ngày | 90 ngày | Điều tra sự cố cũ, compliance |
| ArgoCD replicas | 1 | 2 | |
| **Kiến trúc** | **giống hệt** | **giống hệt** | Staging chỉ có ý nghĩa khi giống prod |

---

## 5. Từ bài học local đến tài nguyên AWS

| Bạn đã học | Local | Production |
|---|---|---|
| Bài 2 | `docker build` | CI build → **ECR** (tag = git SHA, immutable) |
| Bài 3 | `postgres` container + volume | **RDS** Multi-AZ + backup tự động |
| Bài 3 | Mật khẩu trong compose | **Secrets Manager** + ESO |
| Bài 3 | Tên service làm DNS | K8s Service (giữ nguyên!) |
| Bài 3 | `ports: 8081:8081` | **ALB + Ingress**, chỉ user/order được public |
| Bài 4 | Filebeat → ES → Kibana | **CloudWatch** Container Insights |
| Bài 5 | Docker Desktop K8s | **EKS** Auto Mode, 3 AZ |
| Bài 5 | `kubectl apply` bằng tay | **ArgoCD** (GitOps) |
| Bài 5 | `replicas: 3` bằng tay | **HPA** + metrics-server, tự scale theo CPU |
| Bài 5 | Port-forward | ALB + DNS + HTTPS |

---

## 6. Lớp 2: Manifest ứng dụng (GitOps repo, không nằm trong Terraform)

Terraform dừng lại ở việc cung cấp cluster và add-on. Phần của từng service nằm trong một **GitOps repo** mà ArgoCD theo dõi.
Ví dụ cho `orderservice`. Đây là phiên bản production của file bạn viết ở Bài 5:

```yaml
# ExternalSecret: ESO tự tạo K8s Secret "order-db" từ Secrets Manager
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: order-db
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: ClusterSecretStore
    name: aws-secrets-manager
  target:
    name: order-db
  dataFrom:
    - extract:
        key: <secret_arn của order từ output "databases">   # JSON {"username": ..., "password": ...}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orderservice
spec:
  # KHÔNG ghi replicas: để HPA quản lý
  selector:
    matchLabels: { app: orderservice }
  template:
    metadata:
      labels: { app: orderservice }
    spec:
      # Rải pod đều qua các AZ: 1 AZ sập vẫn còn pod ở AZ khác
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels: { app: orderservice }
      containers:
        - name: orderservice
          image: 123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/mcservice/orderservice:a3f9c1e   # tag = git SHA
          ports: [{ containerPort: 8082 }]
          env:
            - name: SPRING_DATASOURCE_URL
              value: jdbc:postgresql://<host order từ output "databases">:5432/order_db
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom: { secretKeyRef: { name: order-db, key: username } }
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom: { secretKeyRef: { name: order-db, key: password } }
            - name: SERVICES_USER_URL
              value: http://userservice:8081          # y hệt Bài 3 và Bài 5
            - name: SERVICES_PAYMENT_URL
              value: http://paymentservice:8083
            - name: LOGGING_STRUCTURED_FORMAT_CONSOLE
              value: ecs
            - name: JAVA_TOOL_OPTIONS
              value: -XX:MaxRAMPercentage=75          # TH 5.8: cho JVM dùng 75% RAM limit thay vì 25%
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits: { memory: 768Mi }
          startupProbe:   { httpGet: { path: /actuator/health/liveness,  port: 8082 }, periodSeconds: 5, failureThreshold: 30 }
          livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8082 }, periodSeconds: 10 }
          readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8082 }, periodSeconds: 5 }
---
# HPA: tự scale 2 → 10 pod theo CPU (cần metrics-server, đã cài ở stack platform)
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: orderservice
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: orderservice }
  minReplicas: 2          # tối thiểu 2: 1 pod chết hoặc đang rolling update vẫn còn 1 pod phục vụ
  maxReplicas: 10
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
---
# PDB: khi AWS thay node (vá bảo mật), không được đuổi quá nhiều pod cùng lúc
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: orderservice
spec:
  minAvailable: 1
  selector:
    matchLabels: { app: orderservice }
---
apiVersion: v1
kind: Service
metadata:
  name: orderservice
spec:
  selector: { app: orderservice }
  ports: [{ port: 8082 }]
---
# Ingress: ALB public. Chỉ userservice (/auth) và orderservice (/orders) có Ingress.
# paymentservice KHÔNG có Ingress → client ngoài không thể gọi thẳng /payments hay /admin/chaos.
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: public-api
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS":443}]'
    alb.ingress.kubernetes.io/certificate-arn: <ARN chứng chỉ ACM của api.shop.com>
spec:
  ingressClassName: alb
  rules:
    - host: api.shop.com
      http:
        paths:
          - { path: /auth,   pathType: Prefix, backend: { service: { name: userservice,  port: { number: 8081 } } } }
          - { path: /orders, pathType: Prefix, backend: { service: { name: orderservice, port: { number: 8082 } } } }
```

Mỗi thay đổi so với Bài 5 đều có lý do: **HPA** + `minReplicas: 2` giữ cho service luôn còn ít nhất một pod phục vụ, **PDB** giới hạn số pod bị đuổi cùng lúc khi AWS bảo trì node, **topologySpread** rải pod qua nhiều AZ, **ExternalSecret** giữ mật khẩu ngoài Git, và **Ingress** chỉ public đúng những gì cần public.

> Cần tạo thêm 1 lần trong GitOps repo: `ClusterSecretStore` tên `aws-secrets-manager` (provider AWS SecretsManager, region `ap-southeast-1`)
> và `IngressClass` tên `alb` (controller `eks.amazonaws.com/alb`).

---

## 7. Luồng deploy trong thiết kế này

```
1. Dev merge PR vào main (repo MCService)
2. GitHub Actions: test → docker build → assume role github-ci (OIDC) → push ECR :a3f9c1e
3. GitHub Actions mở PR vào GitOps repo: đổi image tag staging → a3f9c1e
4. Merge PR → ArgoCD (staging) thấy Git đổi → rolling update                       (TH 5.6)
5. Test trên staging OK → PR promote: đổi image tag prod → a3f9c1e  (CÙNG image)
6. Tech lead approve → merge → ArgoCD (prod) rolling update
7. Lỗi? → revert PR trong GitOps repo → ArgoCD tự quay về bản cũ                   (TH 5.7)
```

Thay đổi **hạ tầng** (ví dụ tăng size RDS) cũng đi qua PR: sửa `prod.tfvars`, CI chạy `terraform plan` và dán kết quả vào PR, người review đọc plan rồi approve, sau đó mới `apply`.
**Không ai chạy `terraform apply` prod từ laptop.**

---

## 8. Cách chạy (tham khảo, ⚠️ tốn tiền)

Yêu cầu: AWS CLI đã đăng nhập, Terraform ≥ 1.10.

```bash
cd infra/terraform

# 0. Bootstrap (1 lần)
cd bootstrap && terraform init && terraform apply && cd ..
#    → sửa bucket trong mọi file *.s3.tfbackend theo output state_bucket

# 1. Global (1 lần) – sửa github_repository trong global/terraform.tfvars trước
cd global && terraform init -backend-config=global.s3.tfbackend && terraform apply && cd ..

# 2. Infrastructure (mỗi môi trường) – mất khoảng 20-30 phút
cd infrastructure
terraform init -reconfigure -backend-config=envs/staging.s3.tfbackend
terraform plan  -var-file=envs/staging.tfvars -out=staging.tfplan    # ĐỌC KỸ PLAN
terraform apply staging.tfplan
cd ..

# 3. Platform (mỗi môi trường)
cd platform
terraform init -reconfigure -backend-config=envs/staging.s3.tfbackend
terraform apply -var-file=envs/staging.tfvars
cd ..

# Kết nối kubectl
aws eks update-kubeconfig --name mcservice-staging --region ap-southeast-1
```

Dọn dẹp theo **thứ tự ngược lại**: platform → infrastructure → global. Nếu Ingress đã tạo ALB, phải xoá Ingress trước, nếu không VPC sẽ không xoá được.

---

## 9. Chi phí ước tính

> **Ước lượng thô** theo giá on-demand, chưa gồm data transfer. Hãy kiểm tra lại bằng [AWS Pricing Calculator](https://calculator.aws/).

| Hạng mục | staging / tháng | prod / tháng |
|---|---|---|
| EKS control plane | ~$73 | ~$73 |
| NAT Gateway | ~$45 (×1) | ~$135 (×3) |
| EC2 node (Auto Mode, gồm phí quản lý) | ~$100 | ~$300+ |
| RDS × 3 | ~$55 (micro, 1 AZ) | ~$450 (medium, Multi-AZ) |
| ALB | ~$20 | ~$30 |
| CloudWatch, Secrets Manager, ECR | ~$20 | ~$60 |
| **Tổng** | **~$300** | **~$1.000+** |

**Bài học:** production "chuẩn" **không hề rẻ**. Với một startup chỉ có 3 service, **1 VM + Docker Compose + RDS** (khoảng $80/tháng) có khi là lựa chọn đúng hơn.
Kiến trúc tốt là kiến trúc **vừa với quy mô và ngân sách**, không phải kiến trúc có nhiều công nghệ nhất.

---

## 10. Chưa có trong thiết kế (nâng cấp tiếp theo)

| Hạng mục | Vì sao cần |
|---|---|
| **Route 53 + ACM** (domain + chứng chỉ HTTPS) | Cần domain thật, nên chưa đưa vào |
| **AWS WAF** trước ALB | Chặn SQL injection, bot, DDoS tầng ứng dụng |
| **Tách AWS account** cho staging/prod (AWS Organizations) | Cô lập tuyệt đối. Hiện 2 môi trường chung 1 account |
| **User DB riêng cho app** + Flyway | App đang dùng user master. Nên tạo user chỉ có quyền trên schema của mình |
| **Terraform chạy trong CI** (Atlantis / GitHub Actions) | `plan` tự động trên PR, `apply` sau khi approve |
| **Prometheus + Grafana, OpenTelemetry** | Metrics nghiệp vụ (số đơn/phút), tracing thay cho `requestId` tự làm |
| **Alarm + on-call** (CloudWatch Alarm → SNS → PagerDuty/Slack) | Tỉ lệ lỗi tăng thì gọi người trực |
| **Backup chéo region** | Cả region Singapore gặp sự cố |
| **Policy-as-code** (Kyverno/OPA) | Chặn deploy pod không có resource limit, image không từ ECR... |
| **Budget alert** | Cảnh báo khi chi phí vượt ngưỡng |

---

## 11. Câu hỏi suy ngẫm

1. Vì sao ECR nằm ở stack `global`, còn RDS lại nằm trong stack theo môi trường?
2. Nếu gộp `platform` vào `infrastructure` thì lần `apply` đầu tiên lỗi ở đâu? Vì sao?
3. `manage_master_user_password = true` giải quyết vấn đề gì mà cách viết `password = var.db_password` vào tfvars không giải quyết được? (Gợi ý: Terraform state chứa gì?)
4. CI chỉ có quyền push ECR, không có quyền vào cluster. Nếu CI bị hack thì thiệt hại tối đa là gì? So với cách CI chạy `kubectl apply` trực tiếp thì sao?
5. Vì sao `minReplicas: 2` mà không phải 1? Liên hệ TH 5.6 (rolling update) và PDB.
6. Nhìn bảng chi phí: nếu công ty chỉ có $150/tháng cho hạ tầng, bạn sẽ bỏ hoặc thay những gì? Mỗi thứ bỏ đi thì bạn chấp nhận rủi ro gì?

**✍️ Trả lời:**
```
1.
2.
3.
4.
5.
6.
```
