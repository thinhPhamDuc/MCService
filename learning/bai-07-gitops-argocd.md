# Bài 7 – GitOps với ArgoCD: push code là tự deploy

> **Mục tiêu:** hoàn thiện pipeline "từ code đến chạy" mà **không ai gõ `kubectl apply`**:
>
> ```
> git push → CI test → build image :sha → CI sửa tag trong Git → ArgoCD thấy Git đổi → rolling update trên K8s
> ```
>
> Toàn bộ chạy trên máy bạn (Docker Desktop K8s) + GitHub, **miễn phí, không cần AWS**.

---

## 1. GitOps là gì?

### Hai kiểu deploy

```
PUSH (kiểu cũ)                                        PULL – GitOps (kiểu mới)
┌────┐   kubectl apply    ┌─────────┐                 ┌────┐  commit   ┌──────┐   ArgoCD tự KÉO về   ┌─────────┐
│ CI │ ─────────────────▶ │ Cluster │                 │ CI │ ────────▶ │ Git  │ ◀─────────────────── │ Cluster │
└────┘  (CI cầm quyền     └─────────┘                 └────┘           └──────┘   (ArgoCD sống trong  │ ArgoCD  │
         admin cluster!)                                                           cluster)            └─────────┘
```

| | Push (CI chạy `kubectl apply`) | Pull / GitOps (ArgoCD) |
|---|---|---|
| Ai cầm quyền vào cluster? | CI. CI bị hack đồng nghĩa với mất cluster | Chỉ ArgoCD **bên trong** cluster. CI chỉ cần quyền ghi Git |
| Cluster đang chạy gì? | Phải `kubectl get` mới biết | **Nhìn Git là biết** |
| Có người sửa tay bằng `kubectl` | Không ai biết, lệch dần theo thời gian | ArgoCD **tự sửa lại** cho khớp Git (self-heal) |
| Rollback | Chạy lại pipeline cũ | `git revert` |
| Lịch sử deploy | Log CI (hết hạn sau vài tháng) | `git log` (vĩnh viễn, có người review) |

**Nguyên tắc GitOps:** Git là **nguồn sự thật duy nhất** (single source of truth). Muốn đổi gì trên cluster thì **đổi Git**.

> Bạn đã gặp tư duy này 2 lần rồi:
> - **K8s** (Bài 5): Deployment khai báo "3 pod", K8s liên tục sửa thực tế cho khớp.
> - **Terraform** (thí nghiệm drift): code nói có container, ai xoá tay thì `apply` tạo lại.
>
> ArgoCD làm **đúng việc đó**, chỉ khác là "trạng thái mong muốn" nằm trong **Git**, và nó tự chạy liên tục chứ không đợi ai gõ lệnh.

---

## 2. Tổ chức manifest: Kustomize

Ở Bài 5, tag image (`userservice:v1`) được viết cứng trong từng file. Mỗi lần deploy thì phải sửa 3 file, lại còn dễ sót.
**Kustomize** (có sẵn trong `kubectl` và ArgoCD) tách **phần không đổi** (base) khỏi **phần thay đổi theo môi trường** (overlay):

```
deploy/
├── base/                         ← manifest Bài 5 (giống nhau mọi môi trường)
│   ├── kustomization.yaml        ← liệt kê các file bên dưới
│   ├── user-db.yaml
│   ├── userservice.yaml
│   ├── payment-db.yaml
│   ├── paymentservice.yaml
│   ├── order-db.yaml
│   └── orderservice.yaml
├── envs/
│   └── local/
│       └── kustomization.yaml    ← "lấy base, đổi image thành GHCR:<sha>, đặt vào namespace mcservice"
└── argocd/
    └── mcservice-local.yaml      ← khai báo cho ArgoCD: "theo dõi deploy/envs/local, đồng bộ vào cluster"
```

`deploy/envs/local/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: mcservice

resources:
  - ../../base

# Deploy = sửa đúng 3 dòng newTag này. Không phải đụng vào file nào khác.
images:
  - name: userservice                                   # khớp với "image: userservice:..." trong base
    newName: ghcr.io/thinhphamduc/mcservice-userservice
    newTag: <sha>
  - name: orderservice
    newName: ghcr.io/thinhphamduc/mcservice-orderservice
    newTag: <sha>
  - name: paymentservice
    newName: ghcr.io/thinhphamduc/mcservice-paymentservice
    newTag: <sha>
```

Muốn có thêm môi trường `staging`? Tạo `envs/staging/` với tag khác hoặc replicas khác. **Base giữ nguyên.**

> 📌 **Production thường tách manifest ra một repo riêng** (GitOps repo), không để chung repo code như bài này. Lý do:
> - Quyền: dev sửa code, nhưng chỉ tech lead được duyệt deploy lên prod.
> - Lịch sử: `git log` của GitOps repo **chính là** lịch sử deploy, không lẫn với commit code.
> - Không bị vòng lặp CI (xem TH 7.6).
>
> Bài này để chung repo cho đơn giản.

---

## 3. Thực hành

### Bước 0 – Điều kiện: Kubernetes + manifest của Bài 5

**0a. Bật Kubernetes trong Docker Desktop** (xem `bai-05-kubernetes.md`, mục "Cách khác không cần cài gì"):

```bash
kubectl config use-context docker-desktop
kubectl get nodes          # docker-desktop   Ready
```

**0b. Có đủ 6 manifest chạy được.** Nếu chưa làm Bài 5, hãy làm mục 4 và 5 của Bài 5: viết 6 file, chạy `kubectl apply -f k8s/`, và thấy 6 pod `Running`.
Đây là nền móng của Bài 7. ArgoCD chỉ **tự động hoá** việc `kubectl apply`, nó không viết manifest hộ bạn.

> ⚠️ Docker Desktop cần **≥ 8GB RAM** (6 pod app + 3 DB + khoảng 7 pod ArgoCD). Tắt compose và ELK trước: `docker compose down`.

### Bước 1 – Chuyển manifest sang cấu trúc Kustomize

```bash
cd ~/Documents/Project/MCService
mkdir -p deploy/base deploy/envs/local deploy/argocd
git mv k8s/*.yaml deploy/base/ 2>/dev/null || mv k8s/*.yaml deploy/base/
```

**1a.** Trong 3 file service ở `deploy/base/`, đổi dòng image về **tên ngắn**. Kustomize sẽ thay tên này bằng tên đầy đủ:

```yaml
          image: userservice          # thay cho userservice:v1
```

(tương tự với `orderservice` và `paymentservice`)

**1b.** Tạo `deploy/base/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - user-db.yaml
  - userservice.yaml
  - ???                 # 4 file còn lại
```

**1c.** Tạo `deploy/envs/local/kustomization.yaml` theo mẫu ở mục 2. Thay `<sha>` bằng SHA **đầy đủ** của commit đã build thành công (tab Actions, hoặc `git log --format=%H -1 origin/main`).

**1d.** Xem trước kết quả mà **không** apply:

```bash
kubectl kustomize deploy/envs/local | grep -E 'image:|namespace:' | sort | uniq -c
```

✅ Thấy 3 dòng `image: ghcr.io/thinhphamduc/mcservice-...:<sha>`, 3 dòng `image: postgres:16-alpine`, và mọi thứ đều nằm trong `namespace: mcservice`.

```bash
git add deploy && git commit -m "deploy: kustomize base + local overlay" && git push
```

### Bước 2 – Cài ArgoCD vào cluster

```bash
kubectl create namespace argocd
kubectl apply -n argocd --server-side --force-conflicts \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl get pods -n argocd -w       # đợi tất cả Running, khoảng 1-2 phút
```

> `--server-side`: các CRD của ArgoCD quá lớn so với kiểu apply thông thường (client-side). Thiếu cờ này sẽ gặp lỗi `metadata.annotations: Too long`.

Mở giao diện web:

```bash
# Mật khẩu admin (tự sinh lúc cài)
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo

# Terminal riêng
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

Mở https://localhost:8443 (trình duyệt cảnh báo chứng chỉ tự ký, bạn chọn "Advanced → Proceed"). Đăng nhập với user `admin` và mật khẩu vừa lấy.

✅ Thấy màn hình ArgoCD trống, chưa có Application nào.

### Bước 3 – Khai báo Application: "hãy theo dõi Git này"

Tạo `deploy/argocd/mcservice-local.yaml`:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: mcservice-local
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/thinhPhamDuc/MCService.git
    targetRevision: main              # theo dõi branch main
    path: deploy/envs/local           # thư mục kustomize
  destination:
    server: https://kubernetes.default.svc   # chính cluster mà ArgoCD đang chạy trong đó
    namespace: mcservice
  syncPolicy:
    automated:
      prune: true        # Git xoá 1 resource -> ArgoCD xoá nó khỏi cluster
      selfHeal: true     # ai sửa tay trên cluster -> ArgoCD sửa lại theo Git
    syncOptions:
      - CreateNamespace=true
```

```bash
# Nếu Bài 5 đã apply bằng tay, xoá đi để ArgoCD tạo lại từ đầu (cẩn thận: xoá cả PVC = mất dữ liệu DB)
kubectl delete namespace mcservice --ignore-not-found

kubectl apply -f deploy/argocd/mcservice-local.yaml
```

> ❓ Đây là lần `kubectl apply` **cuối cùng** bạn cần gõ cho app này. Từ giờ mọi thay đổi đều đi qua Git.

✅ Trên giao diện ArgoCD, app `mcservice-local` chuyển sang **Synced** + **Healthy** (tim xanh). Bấm vào app để xem **sơ đồ** Deployment → ReplicaSet → Pod.

Kiểm tra bằng port-forward + cheatsheet như Bài 5:

```bash
kubectl port-forward -n mcservice svc/userservice 8081:8081
kubectl port-forward -n mcservice svc/orderservice 8082:8082
```

> ⚠️ Nếu pod bị `ImagePullBackOff`: package GHCR đang private. Chuyển sang Public (Bài 6, Bước 5), hoặc tạo imagePullSecret.

> ⏱️ ArgoCD **tự hỏi Git mỗi khoảng 3 phút**. Muốn nhanh hơn thì bấm **Refresh** trên giao diện.
> Trên production, GitHub gọi **webhook** vào ArgoCD ngay khi có push. Máy bạn là `localhost` nên GitHub không gọi vào được.

### Bước 4 – Nối CI với CD: CI tự sửa tag trong Git (bài tập)

Hiện tại CI đã build image `:sha`, nhưng bạn vẫn phải **tự tay** sửa `newTag`. Giờ thêm job thứ 3 vào `.github/workflows/ci.yml`:

```yaml
  update-manifest:
    needs: ???                          # chạy sau job nào?
    runs-on: ubuntu-latest
    permissions:
      contents: ???                     # cần quyền gì để commit + push vào repo?
    steps:
      - uses: actions/checkout@v7

      - name: Set image tags to this commit
        working-directory: deploy/envs/local
        run: |
          for svc in userservice orderservice paymentservice; do
            kustomize edit set image \
              ${svc}=ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/mcservice-${svc}:${{ github.sha }}
          done
          cat kustomization.yaml

      - name: Commit and push
        run: |
          git config user.name  "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add deploy/envs/local/kustomization.yaml
          git commit -m "deploy(local): ${GITHUB_SHA::7}" || echo "Không có gì thay đổi"
          git push
```

> `kustomize` có sẵn trên máy ảo `ubuntu-latest` của GitHub. Nếu báo `command not found`, bạn thêm một bước cài trước (tìm action `imranismail/setup-kustomize`).

✅ **Kiểm tra toàn bộ pipeline:** sửa log `"Login success"` → `"Login success v2"` trong `AuthController`, rồi push. Sau đó theo dõi:

1. Tab Actions: `test` → `build-push` → `update-manifest` đều xanh.
2. `git pull`: có commit mới của `github-actions[bot]` đổi `newTag`.
3. ArgoCD: app chuyển **OutOfSync** → **Syncing** → **Synced** (hoặc bấm Refresh).
4. `kubectl get pods -n mcservice -w`: pod userservice mới lên, pod cũ bị xoá (rolling update).
5. Login rồi xem `kubectl logs -n mcservice deploy/userservice`: thấy `Login success v2`. 🎉

**Bạn vừa có CI/CD hoàn chỉnh: `git push` là code mới tự chạy trên Kubernetes.**

---

## 4. Tình huống

### TH 7.1 — Deploy bằng Git, không bằng kubectl
Trong `deploy/base/paymentservice.yaml`, đổi `replicas: 1` thành `replicas: 2`. Commit, push, rồi bấm Refresh trên ArgoCD.
- Bao lâu sau pod thứ 2 xuất hiện? Nhìn giao diện ArgoCD: trạng thái thay đổi theo thứ tự nào?
- So với `kubectl scale` ở Bài 5: cách nào **để lại dấu vết**, ai đổi, lúc nào, vì sao?

**✍️ Trả lời:**
```

```

### TH 7.2 — Self-heal: "Ai đó sửa tay lúc nửa đêm"
```bash
kubectl scale deploy/paymentservice -n mcservice --replicas=5
kubectl get pods -n mcservice -w
```
- Có 5 pod không? Được bao lâu? Chuyện gì xảy ra sau đó?
- Thử tiếp `kubectl delete deploy/userservice -n mcservice`.
- So sánh với thí nghiệm drift của Terraform: khác nhau ở điểm nào? (Gợi ý: ai phải gõ lệnh để sửa lại?)
- Vậy khi có sự cố 2 giờ sáng, cần scale gấp, bạn làm thế nào?

**✍️ Trả lời:**
```

```

### TH 7.3 — Prune: xoá trong Git thì xoá trên cluster
Tạm bỏ dòng `- paymentservice.yaml` khỏi `deploy/base/kustomization.yaml`, commit rồi push.
- Deployment và Service của payment có bị xoá khỏi cluster không? Tạo order thì sao?
- Nếu `prune: false` thì chuyện gì xảy ra? Trên giao diện ArgoCD, resource đó hiện thế nào?
- ⚠️ Nếu bạn lỡ xoá dòng `- payment-db.yaml` (có chứa PVC) thì sao? Tìm hiểu annotation `argocd.argoproj.io/sync-options: Prune=false` để bảo vệ những resource quan trọng.

(Thêm dòng đó lại sau khi làm xong.)

**✍️ Trả lời:**
```

```

### TH 7.4 — Deploy bản lỗi và Rollback bằng `git revert`
Sửa `newTag` của userservice trong `deploy/envs/local/kustomization.yaml` thành `khong-ton-tai`, rồi commit và push.
- ArgoCD báo **Health** gì? Bấm vào pod lỗi để xem Events.
- Pod userservice **cũ** còn chạy không? Login còn được không? (Liên hệ TH 5.7)
- Rollback:
  ```bash
  git revert HEAD --no-edit && git push
  ```
- Trên giao diện ArgoCD có nút **Rollback** (tab History). Vì sao với GitOps người ta lại ưu tiên `git revert` hơn bấm nút? (Gợi ý: sau khi bấm nút, Git và cluster có còn khớp nhau không? `selfHeal` sẽ làm gì?)

**✍️ Trả lời:**
```

```

### TH 7.5 — Thứ tự khởi động: DB trước, app sau
Xoá sạch rồi để ArgoCD tạo lại: `kubectl delete namespace mcservice`.
- Quan sát `kubectl get pods -n mcservice -w`: app có restart vài lần trong lúc DB chưa sẵn sàng không?
- Hệ thống cuối cùng có tự ổn định không? Nhờ cơ chế nào? (startupProbe, restart)
- Tìm hiểu **sync waves** (`argocd.argoproj.io/sync-wave`): làm sao để ArgoCD tạo DB trước, đợi DB Healthy, rồi mới tạo app?

**✍️ Trả lời:**
```

```

### TH 7.6 — Vòng lặp vô tận?
Job `update-manifest` **push một commit vào `main`**, mà workflow lại chạy **mỗi khi có push vào `main`**. Vậy commit của bot có kích hoạt CI chạy lại → build → commit → chạy lại... mãi mãi không?
- Xem tab Actions sau khi pipeline chạy xong: commit của bot có kích hoạt workflow mới không?
- Tìm hiểu: vì sao commit tạo bằng `GITHUB_TOKEN` **không** kích hoạt workflow? GitHub thiết kế như vậy để chống chuyện gì?
- Nếu bạn đã bật rule "bắt buộc qua PR" cho `main` ở TH 6.2, job `update-manifest` còn push được không? Team thật giải quyết chuyện này thế nào? (Gợi ý: GitOps repo riêng, hoặc bot mở PR)

**✍️ Trả lời:**
```

```

### TH 7.7 — 🔐 Secret trong Git public
Mở https://github.com/thinhPhamDuc/MCService/blob/main/deploy/base/user-db.yaml. Mật khẩu DB đang **công khai cho cả thế giới** đọc.
- Với GitOps, "mọi thứ nằm trong Git". Vậy secret thì sao?
- Tìm hiểu 2 cách phổ biến và so sánh:
  - **Sealed Secrets**: mã hoá secret, chỉ cluster giải mã được, nên commit bản đã mã hoá lên Git an toàn.
  - **External Secrets Operator**: Git chỉ ghi "lấy secret X từ kho", mật khẩu thật nằm trong Vault hoặc AWS Secrets Manager (xem `production-design.md` mục 4.5).
- Nếu một mật khẩu **thật** đã lỡ bị push lên Git, xoá file rồi commit lại có đủ không? Phải làm gì?

**✍️ Trả lời:**
```

```

---

## 5. Câu hỏi nộp bài

1. Vẽ lại toàn bộ luồng từ lúc bạn sửa 1 dòng code đến lúc pod mới chạy. Ghi rõ mỗi bước **ai làm** (bạn, GitHub Actions, GHCR, ArgoCD, K8s).
2. Trong luồng đó, có **bao nhiêu nơi cầm quyền ghi vào cluster**? So với cách CI chạy `kubectl apply` thì an toàn hơn ở điểm nào?
3. ArgoCD bị chết (pod crash). App đang chạy có bị ảnh hưởng không? Cái gì sẽ **không** hoạt động?
4. Muốn thêm môi trường `staging` chạy 2 replica, còn `local` chạy 1 replica, dùng chung base: bạn tổ chức thư mục thế nào? (Gợi ý: Kustomize `patches` hoặc `replicas`)

**✍️ Trả lời:**
```
1.
2.
3.
4.
```

---

## ✅ Checklist

- [ ] Bước 0 – K8s bật, 6 manifest Bài 5 chạy được
- [ ] Bước 1 – Cấu trúc `deploy/base` + `deploy/envs/local`, `kubectl kustomize` ra đúng image
- [ ] Bước 2 – ArgoCD chạy, đăng nhập được giao diện
- [ ] Bước 3 – App `mcservice-local` Synced + Healthy
- [ ] Bước 4 – `git push` code là tự deploy (thấy log "v2")
- [ ] TH 7.1 – Deploy bằng Git
- [ ] TH 7.2 – Self-heal
- [ ] TH 7.3 – Prune
- [ ] TH 7.4 – Rollback bằng git revert
- [ ] TH 7.5 – Thứ tự khởi động / sync waves
- [ ] TH 7.6 – Vòng lặp CI
- [ ] TH 7.7 – Secret trong Git
- [ ] Câu hỏi nộp bài
