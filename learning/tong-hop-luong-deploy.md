# Tổng hợp luồng deploy của MCService (tính đến hết Bài 8)

> Tài liệu này không có bài tập mới. Nó ghép các mảnh từ Bài 2 → Bài 8 thành **một bức tranh**:
> từ lúc bạn sửa một dòng Java đến lúc khách gọi `http://mcservice.localhost/orders` và chạm vào code mới.
>
> Trạng thái được kiểm tra thật trên máy ngày 2026-09-26: CI xanh, ArgoCD `Synced / Healthy`,
> luồng mua hàng qua Gateway trả về `"status":"PAID"`.

---

## 1. Bức tranh tổng thể

```
 ┌──────────── MÁY BẠN ────────────┐
 │ sửa code Java                   │
 │ git pull   ← (bot có thể vừa    │
 │ git push      commit lên main)  │
 └───────────────┬─────────────────┘
                 │ ①
                 ▼
 ┌──────────── GITHUB ─────────────────────────────────────────────────┐
 │ repo main                                                           │
 │   │                                                                 │
 │   ▼ ② GitHub Actions (.github/workflows/ci.yml)                     │
 │   test ──(3 xanh)──► build-push ──(3 image xong)──► update-manifest │
 │   gradlew test       buildx amd64+arm64            sed newTag=<sha> │
 │   ×3 song song       ×3 song song                  bot commit       │
 │                           │                          "deploy(local)"│
 │                           ▼ ③                              │        │
 │                 GHCR: ghcr.io/thinhphamduc/                │        │
 │                   mcservice-<svc>:<sha> và :main           │        │
 └───────────────────────────┬────────────────────────────────┼────────┘
                             │                                │ ④ ArgoCD poll Git (~3 phút)
                             │ ⑥ node kéo image               ▼
 ┌──────────── KUBERNETES (Docker Desktop) ───────────────────────────────┐
 │  ns argocd:   ArgoCD ── thấy newTag đổi ── kubectl apply ──┐           │
 │                                                            ▼ ⑤         │
 │  ns mcservice: Deployment đổi image → rolling update → probe xanh      │
 │                userservice  orderservice  paymentservice  + 3 Postgres │
 │                     ▲             ▲                                    │
 │  ns envoy-gateway-system:  Envoy proxy ◄── HTTPRoute public-api        │
 │                     ▲       (Gateway "public", ns gateway)             │
 └─────────────────────┼──────────────────────────────────────────────────┘
                       │ ⑦ Service LoadBalancer → localhost:80
                 curl http://mcservice.localhost/orders
```

**Việc duy nhất bạn làm bằng tay khi sửa code: `git pull` rồi `git push`.** Từ ② đến ⑦ đều tự động, mất khoảng 5–6 phút.

---

## 2. Từng chặng, chi tiết

### ① Git: nguồn sự thật duy nhất

- Branch `main` quyết định **mọi thứ** chạy trong namespace `mcservice`.
- Luôn `git pull` trước khi push, vì sau mỗi lần build, bot `github-actions[bot]` commit `deploy(local): <sha7>` lên main.
  Quên pull thì push bị từ chối (`rejected ... fetch first`).

### ② CI: `.github/workflows/ci.yml` (Bài 6 + Bài 7)

| Job | Chạy khi | Làm gì | Thất bại thì |
|---|---|---|---|
| `test` | Mọi push + mọi PR | `./gradlew test` cho 3 service, song song (`matrix`) | Dừng. Không có image, không deploy. Báo cáo test được upload làm artifact. |
| `build-push` | Chỉ push lên `main`, sau khi **cả 3** test xanh | Build Dockerfile 2 stage, image **amd64 + arm64**, push lên GHCR | Dừng. Không đổi tag, cluster giữ bản cũ. |
| `update-manifest` | Sau khi **cả 3** image push xong | `sed` mọi dòng `newTag:` trong `deploy/envs/local/kustomization.yaml` thành SHA mới, bot commit + push | Image có trên GHCR nhưng không ai deploy nó. |

Những điểm quan trọng:
- **CI không chạm vào cluster.** Nó chỉ sửa Git. Đây là GitOps kiểu **pull**: ArgoCD tự kéo về.
- Commit của bot dùng `GITHUB_TOKEN`, nên **không kích hoạt lại CI** (nếu có thì sẽ thành vòng lặp vô hạn, xem TH 7.6).
- `concurrency: ci-<branch>` + `cancel-in-progress: true` (ở đầu file): mỗi branch chỉ có **1 lần chạy**, push mới **huỷ** lần chạy cũ đang dở.
  Trước đây chỉ có concurrency cho job bot, nên nếu push A build xong **sau** push B thì bot ghi `newTag = A` và cluster quay về bản cũ (đã sửa, xem mục 6).

### ③ Registry: GHCR (Bài 6)

- Mỗi service có 2 tag: `:<full sha>` (**bất biến**, dùng để deploy) và `:main` (luôn trỏ vào bản mới nhất, chỉ để tiện xem).
- Deploy luôn dùng **SHA**, không dùng `:main` hay `:latest`. Nhờ vậy nhìn Git là biết chính xác code nào đang chạy, và rollback là quay về một SHA cũ.
- Phải **multi-arch**, vì Mac chip Apple là arm64. Image chỉ có amd64 sẽ lỗi khó hiểu `short read: expected ... unexpected EOF`.

### ④ ⑤ GitOps: ArgoCD (Bài 7)

Application `mcservice-local` (`deploy/argocd/mcservice-local.yaml`):

| Thiết lập | Ý nghĩa |
|---|---|
| `path: deploy/envs/local`, `targetRevision: main` | Cluster phải khớp với thư mục này trên main |
| `automated` | Git đổi thì tự apply, không cần bấm Sync |
| `prune: true` | Xoá file khỏi Git thì resource cũng bị xoá khỏi cluster |
| `selfHeal: true` | Ai `kubectl edit` tay thì ArgoCD sửa lại theo Git |
| `CreateNamespace=true` | Tự tạo ns `mcservice` |

**Kustomize** gộp manifest trước khi apply:
```
deploy/base/                 (Bài 5, dùng chung cho mọi môi trường)
  user-db / userservice / payment-db / paymentservice / order-db / orderservice
        +
deploy/envs/local/           (overlay: riêng cho môi trường local)
  namespace: mcservice
  images: newName ghcr.io/... + newTag <sha>      ← bot sửa dòng này
  httproute.yaml                                  ← Bài 8
        =
kubectl kustomize deploy/envs/local               ← đúng thứ ArgoCD apply
```

### ⑥ Kubernetes chạy bản mới (Bài 5)

1. Image trong Deployment đổi, K8s tạo pod mới và kéo image từ GHCR (mạng chậm thì có thể mất vài phút).
2. **startupProbe** chờ Spring Boot khởi động xong, rồi **readinessProbe** xanh thì pod mới được nhận traffic.
3. Sau đó pod cũ mới bị tắt (rolling update). Nếu pod mới không bao giờ xanh, pod cũ **vẫn chạy**: bản lỗi không cướp được traffic (TH 5.11).
4. Các service gọi nhau qua **DNS của Service**, không đi qua Gateway:
   `orderservice → http://userservice:8081` và `http://paymentservice:8083`. Mỗi service nói chuyện với DB riêng: `jdbc:postgresql://order-db:5432/...`.

### ⑦ Traffic từ ngoài vào (Bài 8)

```
curl http://mcservice.localhost/...   (*.localhost = 127.0.0.1)
  → localhost:80 (Docker Desktop đưa Service LoadBalancer của Envoy ra localhost)
  → Envoy proxy đọc HTTPRoute public-api:
       /auth/*    → userservice:8081
       /orders/*  → orderservice:8082
       còn lại    → 404 do Envoy trả (PaymentService không được public)
```
Ngoài ra: `argocd.localhost` → `argocd-server:80`, qua route `deploy/platform/argocd-route.yaml`.

---

## 3. Bản đồ: file nào ↔ ai apply ↔ nằm ở đâu

| File / thành phần | Ai đưa lên cluster | Namespace | Tự đồng bộ theo Git? |
|---|---|---|---|
| `deploy/base/*` + `deploy/envs/local/*` (app, DB, HTTPRoute `public-api`) | **ArgoCD** | `mcservice` | ✅ Có |
| `deploy/envs/local/kustomization.yaml` → `newTag` | **Bot CI** sửa Git | – | ✅ Có |
| `deploy/argocd/mcservice-local.yaml` (Application) | Bạn, `kubectl apply` **1 lần** | `argocd` | ❌ Không |
| `deploy/platform/gateway.yaml` (GatewayClass, Gateway `public`) | Bạn, `kubectl apply` | `gateway` | ❌ Không |
| `deploy/platform/argocd-route.yaml` | Bạn, `kubectl apply` | `argocd` | ❌ Không (và **chưa commit**) |
| ArgoCD v3.5.3 (install.yaml) | Bạn, `kubectl apply --server-side` | `argocd` | ❌ Không |
| Envoy Gateway v1.9.1 (install.yaml) | Claude, `kubectl apply --server-side` | `envoy-gateway-system` | ❌ Không |
| Patch `server.insecure` của ArgoCD | Bạn, `kubectl patch` | `argocd` | ❌ Không, **không có trong Git** |

Nhìn cột cuối: phần **app** đã là GitOps hoàn toàn, còn phần **nền móng/platform** vẫn làm tay.
Nếu xoá cluster tạo lại, phải gõ lại toàn bộ các dòng ❌ theo đúng thứ tự:
Envoy Gateway → ArgoCD (+ patch) → `gateway.yaml` → `argocd-route.yaml` → Application `mcservice-local`.
Sau đó ArgoCD tự dựng lại toàn bộ `mcservice`.

---

## 4. Những gì vẫn còn thủ công hoặc chưa tối ưu

| # | Vấn đề | Hậu quả | Hướng giải quyết (chưa làm) |
|---|---|---|---|
| 1 | `deploy/platform/*` không có Application nào theo dõi | Sửa file mà quên apply thì Git ≠ cluster, không ai báo | Thêm Application trỏ vào `deploy/platform`, rồi tiến lên **app-of-apps** |
| 2 | Nền móng (ArgoCD, Envoy Gateway, patch) cài bằng lệnh tay | Dựng lại cluster phải nhớ cả chuỗi lệnh | App-of-apps, hoặc Helm/Terraform cho bước bootstrap |
| 3 | CI chạy với **mọi** push, không lọc `paths:` | Sửa file `.md` cũng build lại 3 image và **restart cả 3 service** (ví dụ commit `3695ca9 docs: ...`) | `paths-ignore: ['learning/**', '**.md']` và chỉ build service có code thay đổi |
| 4 | Một commit luôn build cả 3 service | Sửa UserService mà Order/Payment cũng bị rolling update | Lọc theo thư mục service, và chỉ `sed` tag của service đó |
| 5 | Secret (password DB `secret`) để plaintext trong repo public | Ai cũng đọc được (TH 7.7) | Sealed Secrets / External Secrets / SOPS |
| 6 | Không có bước duyệt trước khi deploy | Push lên main là deploy luôn | Tạm thời: làm trên branch + PR (mục 6). Khi có nhiều môi trường: staging tự động, production cần duyệt |
| 7 | Log trên K8s chỉ xem bằng `kubectl logs`; ELK (Bài 4) mới chạy với Docker Compose | Pod bị thay là mất log cũ | Đưa Filebeat/ELK (hoặc Loki) lên K8s |
| 8 | **Không có metrics/alert** | Hệ thống hỏng mà chỉ biết khi khách báo | **Bài 9** |

---

## 5. Khi có sự cố: xem chặng nào?

| Triệu chứng | Chặng | Xem ở đâu |
|---|---|---|
| Push xong không có commit `deploy(local)` của bot | ② CI | Tab **Actions** trên GitHub: job nào đỏ? |
| Có commit bot nhưng pod vẫn chạy image cũ | ④ ArgoCD | UI ArgoCD (`argocd.localhost`): `OutOfSync`? Lỗi sync? Chờ đủ ~3 phút chưa? |
| Pod mới `ImagePullBackOff` / `ErrImagePull` | ③ ⑥ | `kubectl describe pod -n mcservice <pod>` → Events. Image có trên GHCR không? Có đủ arm64 không? Mạng có chập chờn không? |
| Pod mới `Running` nhưng `0/1`, hoặc `CrashLoopBackOff` | ⑥ | `kubectl logs -n mcservice <pod>` và `describe` (probe fail?). Pod cũ vẫn phục vụ khách trong lúc đó. |
| `curl mcservice.localhost` → 404, header `server: envoy` | ⑦ | Path không khớp HTTPRoute nào: `kubectl get httproute -A`, `kubectl describe httproute -n mcservice public-api` |
| 404 nhưng body là JSON của Spring | App | Route đúng, nhưng controller không có path đó |
| `curl` không kết nối được | ⑦ | `kubectl get gateway -n gateway` (PROGRAMMED?), `kubectl get svc -n envoy-gateway-system` |
| 500/503 từ `/orders` | App | `kubectl logs -n mcservice deploy/orderservice`: có phải userservice/paymentservice lỗi không? |

**Rollback:** `git revert <commit gây lỗi>` rồi push (hoặc sửa `newTag` về SHA cũ).
**Không** dùng `kubectl set image` hay `kubectl rollout undo`, vì `selfHeal` sẽ đè lại theo Git.

---

## 6. Code tính năng mới khi pipeline đang chạy

**Push lên main không cần chạy thêm lệnh nào.** CI và ArgoCD tự lo phần còn lại.
Nếu muốn theo dõi (không bắt buộc):
```bash
# GitHub → tab Actions: test → build-push → update-manifest đều xanh
git pull && git log --oneline -3          # có commit "deploy(local): <sha7>" của bot
kubectl get pods -n mcservice -w          # pod mới Running 1/1, pod cũ Terminating
kubectl get deploy -n mcservice -o wide   # cột IMAGES = SHA commit của bạn
```

### Những điều cần nhớ

**1. Push lên main = deploy ngay. Tính năng đang làm dở thì để trên branch.**
```bash
git switch -c feature/xyz
# ...code, commit thoải mái...
git push -u origin feature/xyz     # mở Pull Request → CI chỉ chạy TEST, không build, không deploy
# test xanh → merge vào main → lúc này mới deploy
```
`build-push` và `update-manifest` có điều kiện `github.event_name == 'push' && github.ref == 'refs/heads/main'`, nên PR và branch khác không bao giờ được deploy.

**2. Luôn `git pull` trước khi push lên main.** Sau mỗi lần build, bot commit lên main.
Bot **chỉ sửa các dòng `newTag:`** trong `deploy/envs/local/kustomization.yaml`, nên pull không bị conflict, miễn là bạn **không tự sửa các dòng đó**.
Mẹo: `git config pull.rebase true` để lịch sử không bị chen các commit "Merge branch main".

**3. Push nhiều lần sát nhau: đã an toàn.** `ci.yml` có `concurrency` + `cancel-in-progress: true`, nên push mới sẽ huỷ lần chạy cũ.
Trên tab Actions, lần chạy cũ hiện ⊘ **Cancelled**. Đây là **bình thường**, không phải lỗi.

**4. Chạy test ở máy trước khi push.** Chỉ 1 test đỏ là **cả 3 service** đều không deploy.
```bash
cd OrderService && ./gradlew test
```

**5. Đổi Entity / database phải cẩn thận.** Cả 3 service dùng `spring.jpa.hibernate.ddl-auto=update`, và dữ liệu trong PVC được **giữ lâu dài**:

| Bạn làm | Kết quả khi deploy |
|---|---|
| **Thêm** field / cột mới | ✅ Hibernate tự `ALTER TABLE ADD COLUMN` |
| **Đổi tên** field | ⚠️ Tạo cột **mới**, dữ liệu vẫn ở cột cũ. Trông như bị mất dữ liệu. |
| **Xoá** field, đổi kiểu | ⚠️ Cột cũ **không bị xoá**, có thể lỗi khi insert (cột `NOT NULL` còn sót lại) |
| Thêm cột `NOT NULL` không có default | ❌ Bảng đã có dữ liệu thì có thể fail khi khởi động → CrashLoopBackOff (pod cũ vẫn phục vụ) |

Production dùng **Flyway / Liquibase** thay cho `ddl-auto=update`.

**6. Đổi API thì nghĩ tới người gọi nó.**
- Đổi path controller (ví dụ `/orders` → `/api/orders`) thì phải sửa **HTTPRoute** `deploy/envs/local/httproute.yaml`, không thì Envoy trả 404.
- Đổi request/response của UserService hoặc PaymentService thì OrderService đang gọi nó có thể lỗi. Rolling update không đổi 3 service **cùng lúc**, nên có vài chục giây bản mới gọi bản cũ, hoặc ngược lại.
  Chỉ nên **thêm** field. Muốn xoá hoặc đổi tên thì chia làm 2 lần deploy.

**7. Deploy hỏng thì sửa bằng Git.**
```bash
git revert <sha-gây-lỗi> && git push     # ✅
kubectl rollout undo / kubectl edit      # ❌ selfHeal của ArgoCD đè lại theo Git sau vài phút
```

**8. Chạy app ở máy song song với cluster: không xung đột.** Cluster chỉ chiếm cổng **80** (Envoy). `./gradlew bootRun` (8081/8082/8083) hay `docker compose up` vẫn chạy bình thường.
Lưu ý: DB của Compose (volume `mcservice_*-db-data`) và DB trong K8s (PVC) là **2 bộ dữ liệu riêng**.

---

## 7. Tạm dừng K8s để làm việc khác

Chọn **một** trong các cách dưới, tuỳ việc bạn cần:

| Cách | Khi nào dùng | Giải phóng | Giữ lại |
|---|---|---|---|
| **A. Để nguyên** | Máy vẫn đủ RAM | Không | Mọi thứ. Merge vào main vẫn tự deploy. |
| **B. Tắt auto-sync + scale về 0** | Vẫn cần Docker (Compose, `docker build`) nhưng muốn bớt RAM | 3 JVM + 3 Postgres | Cluster, ArgoCD, Gateway, dữ liệu PVC |
| **C. Quit Docker Desktop** | Feature mới **không cần** Docker | Toàn bộ | Mọi thứ (xem mục 8) |
| ❌ Bỏ tick *Enable Kubernetes* / *Reset Kubernetes Cluster* | **Không dùng** | – | **Mất sạch cluster** |

### Cách B: tạm dừng

```bash
# 1. Tắt auto-sync TRƯỚC. Nếu không, selfHeal thấy replicas lệch Git sẽ bật lại ngay
kubectl patch application mcservice-local -n argocd --type merge \
  -p '{"spec":{"syncPolicy":{"automated":null}}}'

# 2. Tắt 6 Deployment (3 service + 3 Postgres). Dữ liệu vẫn nằm trong PVC
kubectl scale deploy -n mcservice --all --replicas=0

kubectl get pods -n mcservice      # No resources found
```
Trong lúc tạm dừng: merge vào main thì CI vẫn build và bot vẫn sửa `newTag`, nhưng **cluster không đổi**.
Trên UI ArgoCD, app hiện **OutOfSync**. Như vậy là đúng.

### Cách B: chạy lại

```bash
# Apply lại Application từ Git: auto-sync + selfHeal được bật lại.
# ArgoCD đưa replicas về như trong Git VÀ deploy luôn newTag mới nhất.
kubectl apply -f deploy/argocd/mcservice-local.yaml

kubectl get pods -n mcservice -w   # đợi 6 pod Running 1/1 (~1–2 phút)
kubectl get application -n argocd  # Synced / Healthy
```
> Hai lệnh `patch` và `scale` ở trên mới được kiểm tra bằng `--dry-run=server`, chưa chạy thật.

---

## 8. Tắt máy rồi bật lại

Cluster K8s của Docker Desktop **lưu trạng thái xuống đĩa**. Tắt máy rồi mở lại Docker Desktop thì mọi thứ ở mục 3
(ArgoCD, Envoy Gateway, Gateway, HTTPRoute, app, **dữ liệu Postgres trong PVC**) tự chạy lại. **Không cần cài lại.**

Việc cần làm sau khi bật máy:
1. **Mở Docker Desktop.** Máy này đang tắt `Start Docker Desktop when you sign in`, nên phải mở tay (hoặc bật tuỳ chọn đó trong Settings → General).
2. Đợi khoảng 2–5 phút cho các pod khởi động lại (image đã có sẵn trong node nên không phải tải lại):
   ```bash
   kubectl get nodes                                   # Ready
   kubectl get pods -A | grep -v Running               # chỉ còn dòng tiêu đề, hoặc vài pod Completed
   kubectl get application -n argocd                   # Synced / Healthy
   kubectl get gateway -n gateway                      # PROGRAMMED True
   curl -i http://mcservice.localhost/orders           # 401 (chưa có token) = Gateway + orderservice đều đã chạy
   ```
3. Nếu trong lúc máy tắt bạn có push code, CI vẫn chạy trên GitHub. Khi máy bật lại, ArgoCD sẽ tự **đuổi kịp** Git.

Những thứ **mất** khi tắt máy: các lệnh `kubectl port-forward` và vòng lặp `while true` đang chạy trong terminal.
Không cần chạy lại port-forward, vì đã có `mcservice.localhost` và `argocd.localhost`.

Những thứ **mất thật sự**: bấm **Reset Kubernetes Cluster** hoặc tắt Kubernetes trong Docker Desktop sẽ xoá sạch cluster.
Khi đó phải dựng lại theo thứ tự ở mục 3.

---

## 9. Hành trình qua các bài

| Bài | Cách deploy lúc đó | Thêm được gì |
|---|---|---|
| 2 | `docker build` + `docker run` tay | Đóng gói app thành image |
| 3 | `docker compose up` | 6 container + mạng + volume chạy cùng nhau |
| 4 | Compose + ELK | Log tập trung, truy vết bằng `requestId` |
| 5 | `kubectl apply -f` tay, image build local | Probe, rolling update, tự khởi động lại pod lỗi |
| 6 | CI build → GHCR, vẫn apply tay | Test tự động, image multi-arch có SHA |
| 7 | CI sửa Git → **ArgoCD tự deploy** | GitOps: push là deploy, Git là nguồn sự thật |
| 8 | Như Bài 7 + **Gateway** | Một cửa vào duy nhất, bỏ port-forward, giấu service nội bộ |
| 9 (sắp tới) | Như Bài 8 | **Nhìn thấy** hệ thống: metrics, dashboard, alert |
| 10 (sắp tới) | Như Bài 9 + message queue | Hệ thống **chịu được lỗi**: retry, circuit breaker, Saga |
