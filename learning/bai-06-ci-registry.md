# Bài 6 – CI + Container Registry (GitHub Actions + GHCR)

> **Mục tiêu:** từ giờ bạn **không còn build image bằng tay**. Push code lên GitHub, máy của GitHub tự chạy test, tự build image,
> rồi tự đẩy image lên registry với tag = commit SHA. Mọi máy (VM, K8s) đều kéo được đúng image đó về chạy.

---

## 1. CI/CD là gì?

```
 Bạn                   GitHub Actions (máy của GitHub, Linux, sạch sẽ mỗi lần chạy)                     Registry (GHCR)
┌─────────┐  push   ┌──────────────────────────────────────────────────────────────────┐   push    ┌──────────────────────────────┐
│ git     │ ──────▶ │ Job "test" (×3 service chạy song song)                           │ ────────▶ │ ghcr.io/<bạn>/mcservice-     │
│ commit  │         │   checkout → cài Java 17 → ./gradlew test                        │   image   │   userservice:<sha>          │
└─────────┘         │        │ ❌ test fail → DỪNG, không build, báo đỏ trên GitHub     │           │   orderservice:<sha>         │
                    │        ▼ ✅                                                        │           │   paymentservice:<sha>       │
                    │ Job "build-push" (chỉ chạy trên branch main)                      │           └──────────────┬───────────────┘
                    │   docker build → login GHCR → docker push :<sha>                  │                          │ docker pull
                    └──────────────────────────────────────────────────────────────────┘                          ▼
                                                                                                    VM (compose) / K8s / EKS
```

| Thuật ngữ | Nghĩa | Bài nào |
|---|---|---|
| **CI** – Continuous Integration | Mỗi lần push: tự **test** và tự **build**. Code hỏng bị chặn ngay | **Bài 6 (bài này)** |
| **Registry** | "Kho" chứa image, giống GitHub nhưng cho image | **Bài 6** |
| **CD** – Continuous Delivery/Deployment | Tự **deploy** image mới lên môi trường | Bài 7+ (ArgoCD) |

### Vì sao không build trên máy mình rồi push?

| Build trên laptop | Build bằng CI |
|---|---|
| "Máy em build được" (mà máy người khác thì không) | Máy sạch, lần nào cũng giống nhau |
| Có thể quên chạy test | Test **bắt buộc**, fail là không có image |
| Không biết image được build từ code nào | Tag = **commit SHA**: từ image truy ngược được đúng dòng code |
| Cần đưa quyền push registry cho từng người | Chỉ CI có quyền push |

---

## 2. Chọn registry

| Registry | Ưu | Nhược |
|---|---|---|
| **GHCR** (GitHub Container Registry) ✅ | Miễn phí cho repo public. **Không cần tạo secret**: dùng luôn `GITHUB_TOKEN` có sẵn trong Actions. Gắn liền với repo | Repo private có thể tính vào quota của GitHub |
| Docker Hub | Phổ biến nhất | Giới hạn số lần pull, phải tạo access token |
| AWS ECR | Dùng khi chạy trên AWS (bộ Terraform `global/ecr.tf`) | Cần tài khoản AWS |

Bài này dùng **GHCR**. Khi lên AWS thì chỉ đổi `registry:` và cách login, phần còn lại của workflow giữ nguyên.

### Tên image và tag

```
ghcr.io / thinhpd / mcservice-orderservice : 3f9c1e2a...   ← tag = commit SHA (bất biến, truy ngược được)
ghcr.io / thinhpd / mcservice-orderservice : main          ← tag "trôi": luôn trỏ bản mới nhất của main
 └ registry  └ owner   └ tên image                └ tag
```

> ⚠️ **Không bao giờ deploy bằng tag `latest` hay `main`.** Hôm nay `:main` là bản A, ngày mai là bản B.
> Server nào pull lúc nào thì chạy code lúc đó, và không ai biết production đang chạy bản nào. **Luôn deploy bằng SHA.**

> ⚠️ Tên image trên GHCR **phải viết thường**. Nếu username GitHub của bạn có chữ hoa (ví dụ `ThinhPD`), workflow phải chuyển sang chữ thường. Mẫu bên dưới đã xử lý chuyện này.

---

## 3. Thực hành

### Bước 1 – Đưa project lên GitHub

**1a.** Tạo file `.gitignore` ở gốc `MCService/`:

```gitignore
.DS_Store
.idea/
*.iml
```

> Mỗi service đã có `.gitignore` riêng (bỏ qua `build/`, `.gradle/`), và `infra/terraform/.gitignore` bỏ qua `*.tfstate`.
> ❓ Mở `infra/terraform/local-demo/terraform.tfstate` ra xem (đã làm ở BT1 bài Terraform). Nếu lỡ commit file này lên repo public thì chuyện gì xảy ra?

**1b.** Tạo repo trên GitHub: vào https://github.com/new, đặt tên `MCService`, chọn **Public** (xem mục 2), **không** tick "Add README".

**1c.** Push lên:

```bash
cd ~/Documents/Project/MCService
git init -b main
git add .
git status                 # ĐỌC KỸ danh sách: có file nào không nên lên không? (tfstate, .env, build/...)
git commit -m "Initial commit: 3 microservices + learning notes"
git remote add origin https://github.com/<username>/MCService.git
git push -u origin main
```

✅ Mở repo trên GitHub, thấy 3 thư mục service. **Không** được có `build/`, `.gradle/`, `terraform.tfstate`.

### Bước 2 – Workflow: chạy test (mẫu đầy đủ)

Tạo file `.github/workflows/ci.yml`:

```yaml
name: CI

# Khi nào chạy?
on:
  pull_request:          # mỗi PR: chạy test để biết có merge được không
  push:
    branches: [main]     # mỗi lần merge vào main: test + build + push image

jobs:
  test:
    runs-on: ubuntu-latest          # máy ảo Linux do GitHub cấp, xoá sạch sau mỗi lần chạy
    strategy:
      fail-fast: false              # 1 service fail thì 2 service kia vẫn chạy tiếp để xem kết quả
      matrix:
        service: [UserService, OrderService, PaymentService]   # nhân job thành 3 bản, chạy SONG SONG
    defaults:
      run:
        working-directory: ${{ matrix.service }}
    steps:
      - uses: actions/checkout@v7            # lấy code về máy ảo

      - uses: actions/setup-java@v6          # cài JDK 17 (khớp toolchain trong build.gradle)
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v6 # cache thư viện Gradle giữa các lần chạy -> lần sau nhanh hơn

      - run: ./gradlew test

      - name: Upload test report
        if: failure()                        # chỉ khi test fail: tải báo cáo HTML về xem lỗi
        uses: actions/upload-artifact@v7
        with:
          name: test-report-${{ matrix.service }}
          path: ${{ matrix.service }}/build/reports/tests/test
```

Push lên, sau đó vào tab **Actions** trên GitHub.

✅ Thấy workflow **CI** với 3 job `test (UserService)`, `test (OrderService)`, `test (PaymentService)`, cả 3 đều xanh.
Bấm vào từng job để xem log từng bước. Trong log, `./gradlew test` báo `BUILD SUCCESSFUL`.

### Bước 3 – Workflow: build và push image (bài tập, điền `???`)

Thêm job thứ 2 vào **cùng file** `ci.yml`, ngay dưới job `test` (thụt lề ngang hàng với `test:`):

```yaml
  build-push:
    needs: ???                  # chỉ chạy khi job nào đã xanh?
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'   # vì sao PR thì KHÔNG push image?
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: ???             # quyền gì để được push lên GHCR?
    strategy:
      matrix:
        include:
          - service: UserService
            image: userservice
          - service: OrderService
            image: orderservice
          - service: PaymentService
            image: paymentservice
    steps:
      - uses: actions/checkout@v7

      # GHCR bắt buộc tên viết thường: "ThinhPD" -> "thinhpd"
      - name: Lowercase owner
        id: owner
        run: echo "name=${GITHUB_REPOSITORY_OWNER,,}" >> "$GITHUB_OUTPUT"

      - uses: docker/setup-buildx-action@v4

      - uses: docker/login-action@v4
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}   # token tự sinh cho mỗi lần chạy, KHÔNG cần tạo secret

      - uses: docker/build-push-action@v7
        with:
          context: ???                             # thư mục chứa Dockerfile của service
          push: true
          tags: |
            ghcr.io/${{ steps.owner.outputs.name }}/mcservice-${{ matrix.image }}:${{ github.sha }}
            ghcr.io/${{ steps.owner.outputs.name }}/mcservice-${{ matrix.image }}:main
          # Cache layer Docker giữa các lần chạy CI (xem Bước 4)
          cache-from: type=gha,scope=${{ matrix.image }}
          cache-to: type=gha,mode=max,scope=${{ matrix.image }}
```

Commit, push, rồi vào tab **Actions**.

✅ Thấy chuỗi `test` (×3) → `build-push` (×3).
✅ Vào trang profile GitHub → tab **Packages**: có 3 package `mcservice-userservice`, `mcservice-orderservice`, `mcservice-paymentservice`.

> 💡 Package mới tạo mặc định là **private**. Vào từng package → **Package settings** → **Change visibility** → Public,
> để kéo image về mà không cần login. (Hoặc giữ private và làm theo mục "Kéo image private" ở Bước 5.)

### Bước 4 – Tối ưu Dockerfile: layer cache (lời hứa từ Bài 2)

**Vấn đề:** Dockerfile hiện tại:

```dockerfile
COPY . .                 # ← sửa 1 dòng code bất kỳ → layer này đổi
RUN ./gradlew bootJar    # ← → layer này chạy lại TỪ ĐẦU: tải Gradle + tải toàn bộ thư viện (vài phút)
```

**Cách Docker cache:** mỗi lệnh là một **layer**. Docker dùng lại layer cũ **nếu lệnh và file đầu vào không đổi**.
Nhưng một khi có một layer phải chạy lại, **tất cả layer phía sau cũng chạy lại**.

**Ý tưởng:** tách phần **ít đổi** (Gradle wrapper, `build.gradle`, tức danh sách thư viện) lên trước, phần **hay đổi** (`src/`) xuống sau:

```dockerfile
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app

# 1. File cấu hình build (hiếm khi đổi)
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# 2. Tải Gradle + thư viện -> layer này được CACHE, chỉ chạy lại khi build.gradle đổi
RUN ./gradlew dependencies --no-daemon > /dev/null

# 3. Code (hay đổi) -> chỉ từ đây trở xuống chạy lại
COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
# Chạy bằng user thường, không phải root: app bị hack cũng không có quyền root trong container
RUN useradd --system --uid 1001 app
USER app
COPY --from=builder /app/build/libs/???-0.0.1-SNAPSHOT.jar app.jar
EXPOSE ???
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Áp dụng cho cả 3 service, chú ý 2 chỗ `???` khác nhau giữa các service.

**Đo thử ở local:**

```bash
time docker build -t userservice:cache-test ./UserService    # lần 1: đo thời gian
# sửa 1 dòng log trong AuthController
time docker build -t userservice:cache-test ./UserService    # lần 2: nhanh hơn bao nhiêu? Bước nào ghi "CACHED"?
```

### Bước 5 – Kéo image từ registry về chạy

Đây là cách một **server thật** nhận code mới: nó **không build**, nó chỉ **pull** image mà CI đã build sẵn.

```bash
# Lấy SHA của commit mới nhất
git rev-parse HEAD

docker pull ghcr.io/<owner-viết-thường>/mcservice-userservice:<sha>
```

**Kéo image private:** tạo Personal Access Token (Settings → Developer settings → Tokens (classic) → quyền `read:packages`), rồi:

```bash
echo <token> | docker login ghcr.io -u <username> --password-stdin
```

**Sửa compose để chạy image từ registry** thay vì build. Với mỗi service, thay `build:` bằng `image:`:

```yaml
  userservice:
    image: ghcr.io/<owner>/mcservice-userservice:${TAG}
    # build: ./UserService     ← bỏ đi
```

```bash
TAG=<sha> docker compose up -d
```

Đây chính là cách deploy phổ biến của các team nhỏ: **CI build, rồi VM chỉ cần `pull` + `up`.**

**(Tuỳ chọn, nếu đã làm Bài 5)** Sửa `image:` trong `k8s/*.yaml` thành image GHCR + SHA, đổi `imagePullPolicy: IfNotPresent`, rồi `kubectl apply`.
Nếu package private, cluster cần một **imagePullSecret**:

```bash
kubectl create secret docker-registry ghcr --docker-server=ghcr.io \
  --docker-username=<username> --docker-password=<token>
```

rồi thêm vào `spec.template.spec` của Deployment:

```yaml
      imagePullSecrets:
        - name: ghcr
```

---

## 4. Tình huống

### TH 6.1 — "Test đỏ thì chuyện gì xảy ra?"
Tạo branch mới, cố ý làm hỏng logic: trong `PaymentController`, đổi `compareTo(maxAmount) > 0` thành `>= 0`. Push lên rồi mở **Pull Request**.
- Job nào đỏ? Test nào fail? Đọc log để tìm **tên test** và **dòng lỗi**.
- Tải `test-report-PaymentService` (phần Artifacts) về, mở `index.html`.
- Job `build-push` có chạy không? Vì sao?
- Nếu cứ bấm Merge thì sao? (Xem TH 6.2.)

**✍️ Trả lời:**
```

```

### TH 6.2 — Chặn merge khi CI đỏ
Settings → **Rules** (hoặc Branches) → tạo rule cho `main`: *Require a pull request before merging* + *Require status checks to pass* (chọn 3 job test).
(Tính năng này miễn phí với repo **public**. Repo private cần gói GitHub trả phí.)
- Mở lại PR ở TH 6.1: nút Merge giờ trông thế nào?
- Thử `git push origin main` trực tiếp (không qua PR). Có được không?
- Vì sao team thật **bắt buộc** mọi thay đổi phải qua PR?

**✍️ Trả lời:**
```

```

### TH 6.3 — Cache nhanh đến mức nào?
Sau khi làm Bước 4, so sánh thời gian job `build-push` giữa:
1. Lần chạy **đầu tiên** (chưa có cache)
2. Lần chạy sau khi **chỉ sửa 1 dòng code** trong `src/`
3. Lần chạy sau khi **thêm 1 thư viện** vào `build.gradle`

| Lần | Thời gian build-push | Bước nào CACHED? |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |

**✍️ Giải thích:**
```

```

### TH 6.4 — Sửa 1 service, build cả 3?
Sửa log trong **PaymentService** rồi push. Xem tab Actions: có bao nhiêu image được build lại?
- Với 3 service thì lãng phí này chấp nhận được. Nếu có 30 service thì sao?
- Tìm hiểu cách chỉ build service có thay đổi. (Gợi ý: `on.push.paths`, tách mỗi service một workflow, hoặc action `dorny/paths-filter`.)
- ⚠️ Nếu bạn sửa file dùng chung (ví dụ `docker-compose.yml`) thì nên build service nào?

**✍️ Trả lời:**
```

```

### TH 6.5 — Rollback bằng SHA
Deploy bằng compose với `TAG=<sha mới nhất>`. Sau đó giả sử bản này lỗi, bạn cần quay lại bản trước.
- Tìm SHA bản trước ở đâu? (`git log --oneline`, tab Actions, tab Packages)
- Rollback bằng lệnh gì? Mất bao lâu? Có phải build lại không?
- Nếu mọi người deploy bằng tag `:main` thì rollback thế nào?

**✍️ Trả lời:**
```

```

### TH 6.6 — Bảo mật CI
- `GITHUB_TOKEN` sống bao lâu? Vì sao dùng nó an toàn hơn tạo một Personal Access Token rồi lưu vào Secrets?
- Vì sao chỉ job `build-push` có `packages: write`, còn job `test` thì không?
- Một người lạ **fork** repo của bạn, sửa workflow để in `GITHUB_TOKEN` ra log, rồi mở PR. Họ có lấy được quyền push image không? (Tìm hiểu: token của PR từ fork có quyền gì?)
- Liên hệ `infra/terraform/global/github-oidc.tf`: trên AWS, vì sao dùng OIDC thay cho access key?

**✍️ Trả lời:**
```

```

### TH 6.7 — "Máy em chạy được mà CI lại fail"
Git lưu cả **quyền thực thi** của file. Thử gỡ quyền thực thi của `gradlew` **chỉ trong Git**, còn file trên máy bạn vẫn giữ nguyên:

```bash
git update-index --chmod=-x UserService/gradlew
git commit -m "test: remove exec bit" && git push
```

- Ở local, `cd UserService && ./gradlew test` có chạy được không? Trên CI, job `test (UserService)` báo lỗi gì?
- Nhớ lại câu hỏi "`./gradlew: Permission denied`" ở Bài 2: bây giờ bạn đã biết nguyên nhân chưa?
- Kể thêm 2 lý do khác khiến "local pass, CI fail". (Gợi ý: phiên bản Java, biến môi trường, file bị `.gitignore` nên không lên repo, Mac không phân biệt chữ hoa/thường trong tên file còn Linux thì có.)

Sửa lại: `git update-index --chmod=+x UserService/gradlew`, rồi commit và push.

**✍️ Trả lời:**
```

```

---

## 5. Câu hỏi nộp bài

1. CI khác CD thế nào? Bài này đã làm được phần nào, còn thiếu phần nào để "push code là tự lên production"?
2. Vì sao image tag nên là commit SHA thay vì số phiên bản tự đặt (`v1`, `v2`) như Bài 5?
3. Vì sao **không** chạy `./gradlew test` bên trong Dockerfile, mà tách thành job `test` riêng?
4. Image của bạn nặng khoảng 550MB. Đề xuất 2 cách làm nhỏ lại. (Gợi ý: image base `-alpine` hoặc distroless; jlink)

**✍️ Trả lời:**
```
1.
2.
3.
4.
```

---

## ✅ Checklist

- [ ] Bước 1 – Code đã lên GitHub, không có file rác hay nhạy cảm
- [ ] Bước 2 – Job test xanh cho 3 service
- [ ] Bước 3 – Job build-push xanh, có 3 package trên GHCR
- [ ] Bước 4 – Dockerfile tối ưu cache + chạy non-root cho cả 3 service
- [ ] Bước 5 – Pull image từ GHCR, chạy bằng compose với `TAG=<sha>`
- [ ] TH 6.1 – Test đỏ
- [ ] TH 6.2 – Chặn merge
- [ ] TH 6.3 – Đo cache
- [ ] TH 6.4 – Build có chọn lọc
- [ ] TH 6.5 – Rollback bằng SHA
- [ ] TH 6.6 – Bảo mật CI
- [ ] TH 6.7 – Local pass, CI fail
- [ ] Câu hỏi nộp bài
