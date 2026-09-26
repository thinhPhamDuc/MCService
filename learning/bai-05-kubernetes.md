# Bài 5 – Kubernetes với Minikube

> **Mục tiêu:** chạy lại toàn bộ hệ thống trên Kubernetes, rồi hiểu những gì K8s làm được mà Docker Compose **không** làm được:
> tự hồi phục, scale, rolling update (deploy không downtime) và rollback.

---

## 1. Vì sao đã có Compose rồi còn cần Kubernetes?

| Vấn đề | Docker Compose | Kubernetes |
|---|---|---|
| Chạy trên bao nhiêu máy? | **1 máy** | **Cụm nhiều máy** (cluster) |
| Container chết | Nằm im (trừ khi cấu hình `restart`) | **Tự tạo lại**, và có thể chuyển sang máy khác |
| Máy chủ chết | Cả hệ thống chết | Pod được dời sang máy còn sống |
| Scale | `--scale`, dễ đụng port, không load balance tốt | `replicas: 3`, Service **tự chia tải** |
| Deploy phiên bản mới | `down` rồi `up`, nên **có downtime** | **Rolling update**: thay dần từng pod, không downtime |
| Deploy lỗi | Tự sửa tay | `kubectl rollout undo` |
| Biết app đã sẵn sàng? | healthcheck (chỉ dùng cho `depends_on`) | **Probe**: chưa sẵn sàng thì không nhận traffic, treo thì bị restart |

> **Tư duy cốt lõi:** với Compose, bạn ra lệnh *"hãy chạy container này"*.
> Với K8s, bạn **khai báo trạng thái mong muốn**, ví dụ *"tôi muốn luôn có 3 pod paymentservice khoẻ mạnh"*.
> K8s liên tục so sánh **trạng thái thực tế** với **trạng thái mong muốn** rồi tự sửa cho khớp. Cơ chế này gọi là *reconciliation loop*.

## 2. Từ Compose sang K8s: bảng chuyển đổi

| Trong `docker-compose.yml` | Trong Kubernetes | Ghi chú |
|---|---|---|
| Một `service` | **Deployment** + **Service** | Deployment lo *chạy pod*, Service lo *địa chỉ mạng* |
| Container | **Pod** (chứa 1 hoặc vài container) | Đơn vị nhỏ nhất K8s quản lý |
| Tên service làm DNS (`userservice`) | **Service** (ClusterIP) | Vẫn gọi `http://userservice:8081` như cũ 🎉 |
| `environment:` | `env:` / **ConfigMap** | |
| Mật khẩu viết thẳng trong file | **Secret** | |
| `volumes:` | **PersistentVolumeClaim (PVC)** | |
| `healthcheck:` | **startupProbe / readinessProbe / livenessProbe** | |
| `depends_on:` | ❌ Không có | App phải tự chịu được khi dependency chưa sẵn sàng |
| `ports: "8081:8081"` | `kubectl port-forward` / NodePort / Ingress | |
| `--scale x=3` | `replicas: 3` | |
| `restart: on-failure` | Có sẵn mặc định | |

```
                    Kubernetes cluster (minikube)
┌───────────────────────────────────────────────────────────────────┐
│                                                                   │
│  Service "orderservice" ──▶ Pod order ─┬─▶ Service "userservice" ──▶ Pod user ──▶ Service "user-db" ──▶ Pod postgres ──▶ PVC
│         ▲                              │                                                                   │
│         │                              └─▶ Service "paymentservice" ─┬─▶ Pod payment #1                    │
│         │                                   (tự chia tải)            ├─▶ Pod payment #2  ──▶ ... payment-db│
│         │                                                            └─▶ Pod payment #3                    │
└─────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┘
          │ kubectl port-forward
       bạn (curl)
```

### 3 loại probe (rất quan trọng)

| Probe | Câu hỏi K8s đặt ra | Nếu thất bại |
|---|---|---|
| **startupProbe** | "App khởi động xong chưa?" (Java cần 10 đến 30 giây) | Chờ tiếp. Quá `failureThreshold` lần thì restart |
| **readinessProbe** | "App có sẵn sàng nhận request không?" | **Rút pod khỏi Service**, tức là không gửi traffic tới, nhưng **không restart** |
| **livenessProbe** | "App còn sống không, hay đang bị treo?" | **Restart container** |

> Spring Boot tự bật 2 endpoint `/actuator/health/liveness` và `/actuator/health/readiness` khi phát hiện đang chạy trong K8s.
> Code không cần sửa gì.

---

## 3. Cài đặt

> ### 🔀 Cách khác không cần cài gì: Kubernetes có sẵn trong Docker Desktop
>
> 1. `docker compose down`
> 2. Docker Desktop → Settings → **Kubernetes** → tick **Enable Kubernetes** → nếu có mục *Cluster provisioning method*, chọn **kubeadm** → Apply & Restart
> 3. `kubectl config use-context docker-desktop` → `kubectl get nodes` ra `docker-desktop Ready`
>
> Những chỗ khác so với hướng dẫn Minikube bên dưới:
>
> | Minikube | Docker Desktop |
> |---|---|
> | `minikube start` | Bỏ qua |
> | `eval $(minikube docker-env)` | **Bỏ qua**, `docker build` bình thường là cluster thấy image |
> | Namespace, manifest, port-forward | Giữ nguyên |
> | `minikube delete` | `kubectl delete namespace mcservice`, hoặc Settings → Kubernetes → Reset Kubernetes Cluster |
> | TH 5.1: build ở terminal chưa chạy docker-env | Dùng tag chưa build: `kubectl set image deploy/userservice userservice=userservice:v2` |
>
> Nếu pod báo `ErrImagePull` với image vừa build, có thể Docker Desktop đang chạy kiểu cluster **kind** (không dùng chung image). Khi đó đổi sang **kubeadm**.
>
> Tải Minikube không qua brew (Mac Apple chip):
> ```bash
> curl -LO https://github.com/kubernetes/minikube/releases/latest/download/minikube-darwin-arm64
> sudo install minikube-darwin-arm64 /usr/local/bin/minikube && rm minikube-darwin-arm64
> ```

### Bước 1 – Giải phóng tài nguyên và cài Minikube

```bash
cd ~/Documents/Project/MCService
docker compose down            # tắt hệ thống Bài 3/4 (KHÔNG có -v, nên dữ liệu vẫn giữ)
brew install minikube
```

Docker Desktop → Settings → Resources → **Memory ≥ 8 GB**, vì Minikube chạy *bên trong* Docker.

```bash
minikube start --driver=docker --cpus=4 --memory=6g
kubectl get nodes
```

✅ Thấy 1 node `minikube` ở trạng thái `Ready`.

### Bước 2 – Tạo namespace riêng

```bash
kubectl create namespace mcservice
kubectl config set-context --current --namespace=mcservice   # từ giờ mọi lệnh mặc định chạy trong mcservice
```

> Namespace giống như một "thư mục" trong cluster, giúp tách biệt các project hoặc môi trường.

### Bước 3 – Build image vào bên trong Minikube

Minikube có **Docker daemon riêng**, tách biệt với Docker trên máy bạn. Image bạn build bằng Docker Desktop thì Minikube **không nhìn thấy**.

```bash
eval $(minikube docker-env)       # chuyển lệnh docker của terminal NÀY sang daemon của Minikube
docker build -t userservice:v1    ./UserService
docker build -t orderservice:v1   ./OrderService
docker build -t paymentservice:v1 ./PaymentService
docker images | grep service      # 3 image phải nằm trong danh sách
```

> ⚠️ `eval $(minikube docker-env)` chỉ có tác dụng trong **terminal hiện tại**. Mở terminal mới thì phải chạy lại.
> Khi muốn quay lại dùng Docker Desktop: `eval $(minikube docker-env -u)`.

---

## 4. Viết manifest

Tạo thư mục `k8s/` ở gốc `MCService/`. Mình viết mẫu **đầy đủ cho User** (DB + service), còn Payment và Order bạn tự viết.

### 4.1 `k8s/user-db.yaml` (mẫu đầy đủ)

```yaml
# Secret: thông tin đăng nhập DB (tách khỏi Deployment để quản lý riêng)
apiVersion: v1
kind: Secret
metadata:
  name: user-db-secret
type: Opaque
stringData:
  POSTGRES_DB: user_db
  POSTGRES_USER: app
  POSTGRES_PASSWORD: secret
---
# PVC: "xin" 1GB ổ đĩa lâu dài cho Postgres (tương đương named volume trong compose)
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: user-db-data
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 1Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-db
spec:
  replicas: 1
  strategy:
    type: Recreate          # DB: tắt pod cũ rồi mới bật pod mới (không cho 2 Postgres cùng ghi 1 ổ đĩa)
  selector:
    matchLabels:
      app: user-db
  template:
    metadata:
      labels:
        app: user-db        # nhãn này để Service tìm ra pod
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          envFrom:
            - secretRef:
                name: user-db-secret     # nạp toàn bộ key trong Secret thành biến môi trường
          ports:
            - containerPort: 5432
          readinessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U app -d user_db"]
            periodSeconds: 5
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: user-db-data
---
# Service: tạo tên DNS "user-db" trỏ vào pod có label app=user-db
apiVersion: v1
kind: Service
metadata:
  name: user-db
spec:
  selector:
    app: user-db
  ports:
    - port: 5432
```

> 📌 Thực tế trên production, database thường **không** chạy trong K8s như thế này. Người ta dùng dịch vụ managed (AWS RDS, Cloud SQL...) hoặc **StatefulSet**.
> Ở đây dùng Deployment cho dễ học.

### 4.2 `k8s/userservice.yaml` (mẫu đầy đủ)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: userservice
spec:
  replicas: 1
  selector:
    matchLabels:
      app: userservice
  template:
    metadata:
      labels:
        app: userservice
    spec:
      containers:
        - name: userservice
          image: userservice:v1
          imagePullPolicy: IfNotPresent   # có image sẵn trong minikube thì dùng, không có thì kéo từ Docker Hub
          ports:
            - containerPort: 8081
          env:
            - name: SPRING_DATASOURCE_URL
              value: jdbc:postgresql://user-db:5432/user_db
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: user-db-secret
                  key: POSTGRES_USER
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: user-db-secret
                  key: POSTGRES_PASSWORD
          resources:
            requests:               # K8s đảm bảo dành ít nhất chừng này cho pod (dùng để chọn node)
              cpu: 250m             # 0.25 CPU
              memory: 384Mi
            limits:
              memory: 512Mi         # vượt mức này thì container bị kill (OOMKilled)
          startupProbe:             # cho Java tối đa 30 x 5s = 150s để khởi động
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            periodSeconds: 5
            failureThreshold: 30
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: userservice
spec:
  selector:
    app: userservice
  ports:
    - port: 8081          # port của Service (các pod khác gọi vào đây)
      targetPort: 8081    # port của container
```

### 4.3 Bài tập: `k8s/payment-db.yaml` và `k8s/paymentservice.yaml`

Copy 2 file của User rồi đổi tên. Bạn **tự liệt kê** những chỗ phải đổi; có ít nhất **8 chỗ**, gồm tên, label, DB name, port, secret và image.

> 💡 Mẹo kiểm tra: `grep -n user k8s/payment*.yaml` phải **không in ra dòng nào**. Nếu có, nghĩa là bạn còn sót chỗ chưa đổi.

### 4.4 Bài tập: `k8s/order-db.yaml` và `k8s/orderservice.yaml`

`order-db.yaml` làm tương tự. Riêng `orderservice.yaml`, phần `env` cần thêm:

```yaml
          env:
            - name: SPRING_DATASOURCE_URL
              value: ???
            # ... USERNAME / PASSWORD lấy từ secret nào? ...
            - name: SERVICES_USER_URL
              value: ???            # So với compose có phải đổi không? Vì sao?
            - name: SERVICES_PAYMENT_URL
              value: ???
```

> ❓ Trong compose, `orderservice` có `depends_on`. K8s **không có** khái niệm này.
> Vậy nếu orderservice khởi động **trước** order-db thì chuyện gì xảy ra? Hệ thống cuối cùng có tự ổn định không? (Bạn sẽ quan sát được ở Bước 5.)

---

## 5. Deploy

```bash
kubectl apply -f k8s/                  # apply toàn bộ file trong thư mục
kubectl get pods -w                    # -w: theo dõi realtime, Ctrl+C để thoát
```

✅ Sau khoảng 1 đến 2 phút, 6 pod đều ở trạng thái `Running` và cột READY là `1/1`.

> Quan sát kỹ cột **RESTARTS** trong lúc chờ. Có pod nào restart không? Vì sao? (Liên hệ câu hỏi `depends_on` ở 4.4.)

### Truy cập từ máy bạn

Service kiểu ClusterIP chỉ truy cập được **bên trong cluster**. Để curl từ máy bạn, mở **2 terminal riêng**:

```bash
kubectl port-forward svc/userservice  8081:8081     # terminal 1
kubectl port-forward svc/orderservice 8082:8082     # terminal 2
```

Sau đó dùng lại toàn bộ lệnh trong `curl-cheatsheet.md` như bình thường. 🎉

> ❓ Ta **không** port-forward paymentservice. Vậy client ngoài có gọi thẳng được `/payments` không?
> Đây chính là câu trả lời cho câu hỏi "ai cũng gọi thẳng được PaymentService" trong cheatsheet.
> (Khi cần dùng `/admin/chaos` thì bạn mới port-forward tạm: `kubectl port-forward svc/paymentservice 8083:8083`.)

---

## 6. Lệnh kubectl cần thuộc

```bash
kubectl get pods                          # danh sách pod
kubectl get pods -o wide                  # thêm IP, node
kubectl get all                           # pod + deployment + service + replicaset
kubectl describe pod <tên-pod>            # CHI TIẾT + phần Events ở cuối (đọc đầu tiên khi pod lỗi!)
kubectl logs <tên-pod>                    # log
kubectl logs -f deploy/orderservice       # log realtime theo deployment
kubectl logs <tên-pod> --previous         # log của container TRƯỚC khi bị restart (để xem vì sao nó chết)
kubectl logs -l app=paymentservice --prefix   # log của tất cả pod có label đó, có ghi tên pod ở đầu dòng
kubectl exec -it <tên-pod> -- sh          # chui vào trong container
kubectl get events --sort-by=.lastTimestamp   # dòng thời gian mọi sự kiện trong namespace
kubectl apply -f k8s/xxx.yaml             # tạo hoặc cập nhật
kubectl delete -f k8s/xxx.yaml            # xoá
kubectl scale deploy/paymentservice --replicas=3
kubectl rollout status deploy/userservice
kubectl rollout history deploy/userservice
kubectl rollout undo deploy/userservice
```

Xem DB:

```bash
kubectl exec deploy/order-db -- psql -U app -d order_db -c "select id, status, failure_reason from orders;"
```

> 🔍 **Quy trình debug pod lỗi:** `get pods` (xem STATUS) → `describe pod` (đọc Events) → `logs` / `logs --previous`.
> 90% lỗi tìm ra được bằng 3 lệnh này.

---

## 7. Tình huống

> Như các bài trước: **đoán trước**, gây lỗi, quan sát, giải thích.

### TH 5.1 — `ErrImagePull`: "Image có sẵn trên máy mà sao K8s không thấy?"
Mở **terminal mới** (chưa chạy `minikube docker-env`), chạy `docker build -t userservice:v2 ./UserService`,
sau đó sửa `image: userservice:v2` trong `userservice.yaml` và `kubectl apply`.
- `kubectl get pods`: STATUS là gì?
- `kubectl describe pod ...`: phần Events báo gì? K8s đang cố kéo image **từ đâu**?
- Pod v1 cũ còn chạy không? Login còn được không? Vì sao?
- Sửa thế nào cho đúng?

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

### TH 5.2 — Self-healing: "Lỡ tay xoá pod production!"
```bash
kubectl get pods
kubectl delete pod <tên-pod-userservice>
kubectl get pods -w
```
- Chuyện gì xảy ra? Pod mới có **cùng tên** với pod cũ không?
- Ai đã tạo lại pod? (Gợi ý: `kubectl get replicaset`)
- So sánh với `docker rm -f` một container trong compose.
- Vậy làm sao để **thật sự** tắt userservice?

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

### TH 5.3 — Scale và load balancing
```bash
kubectl scale deploy/paymentservice --replicas=3
kubectl get pods -l app=paymentservice
```
Tạo khoảng 10 order (vòng lặp ở mục 6 của cheatsheet), rồi chạy `kubectl logs -l app=paymentservice --prefix | grep "Processing payment"`.
- Request có được chia cho các pod không? So với TH8 của Bài 3 thì sao?
- Lần này có bị lỗi trùng port như với compose không? Vì sao?
- Port-forward svc/paymentservice rồi gọi `/admin/chaos?errorRate=1`. Có phải **cả 3 pod** đều lỗi không? Vì sao? (Gợi ý: port-forward kết nối tới mấy pod?)

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

### TH 5.4 — `CrashLoopBackOff`
Sửa `POSTGRES_PASSWORD` **chỉ** trong phần userservice lấy ra. Cách làm: tạo thêm key `WRONG_PASSWORD: sai` trong `user-db-secret`, rồi đổi `key: POSTGRES_PASSWORD` thành `key: WRONG_PASSWORD` trong `userservice.yaml`. Sau đó `kubectl apply -f k8s/`.
- `kubectl get pods -w`: STATUS thay đổi thế nào? Cột RESTARTS ra sao? Thời gian giữa các lần restart **dài dần** hay cố định?
- `kubectl logs <pod> --previous`: lỗi gì?
- Trong lúc đó, pod userservice **cũ** (password đúng) có còn phục vụ không? Vì sao K8s chưa xoá nó?

(Sửa lại cho đúng sau khi làm xong.)

**✍️ Trả lời:**
```
Đoán:
Thực tế:
Vì sao:
```

### TH 5.5 — Readiness và Liveness: 2 probe hỏng cho 2 kết quả khác nhau
**Lần A:** đổi path của `readinessProbe` thành `/actuator/health/wrong`, rồi apply.
**Lần B:** trả readiness về đúng, sau đó đổi path của `livenessProbe` thành `/actuator/health/wrong`, rồi apply.

| | STATUS | READY | RESTARTS | Login còn được không? |
|---|---|---|---|---|
| Lần A (readiness sai) | | | | |
| Lần B (liveness sai) | | | | |

- Giải thích vì sao 2 lần cho kết quả khác nhau.
- ❓ Nếu **user-db chết** (`kubectl scale deploy/user-db --replicas=0`) thì pod userservice có bị restart không? Có nên để liveness kiểm tra luôn cả DB không? (Gợi ý: DB chết 1 phút thì *tất cả* pod service bị restart liên tục. Như vậy có giúp được gì không?)

**✍️ Trả lời:**
```

```

### TH 5.6 — 🚀 Rolling update: deploy không downtime
1. Sửa log trong `AuthController`: `"Login success"` → `"Login success v2"`.
2. Trong terminal đã chạy `eval $(minikube docker-env)`, chạy `docker build -t userservice:v2 ./UserService`.
3. Để **UserService có 2 replicas** trước.
4. Ở một terminal khác, chạy vòng lặp login liên tục:
   ```bash
   while true; do curl -s -o /dev/null -w "%{http_code} " -X POST localhost:8081/auth/login \
     -H 'Content-Type: application/json' -d '{"username":"alice","password":"123456"}'; sleep 0.3; done
   ```
5. Deploy v2:
   ```bash
   kubectl set image deploy/userservice userservice=userservice:v2
   kubectl rollout status deploy/userservice
   ```
- Trong lúc chuyển phiên bản, vòng lặp có ra mã nào khác `200` không?
- `kubectl get pods -w` trong lúc deploy: pod cũ và pod mới thay nhau như thế nào?
- ⚠️ Lưu ý: `port-forward` gắn cố định vào **1 pod**. Khi pod đó bị xoá thì port-forward đứt. Đây là **hạn chế của port-forward**, không phải lỗi của K8s. Khi đó Service thật (bên trong cluster, ví dụ khi OrderService gọi UserService) có bị gián đoạn không? Làm sao để kiểm chứng?

**✍️ Trả lời:**
```

```

### TH 5.7 — Deploy lỗi và Rollback
```bash
kubectl set image deploy/userservice userservice=userservice:v999    # phiên bản không tồn tại
kubectl rollout status deploy/userservice                            # treo, Ctrl+C
kubectl get pods
```
- Hệ thống còn phục vụ được không? Vì sao K8s không xoá hết pod cũ?
- Rollback:
  ```bash
  kubectl rollout history deploy/userservice
  kubectl rollout undo deploy/userservice
  ```
- So sánh: với compose, nếu deploy image lỗi thì chuyện gì xảy ra?

**✍️ Trả lời:**
```

```

### TH 5.8 — `OOMKilled`
Đổi `limits.memory` của paymentservice thành `150Mi`, rồi apply.
- STATUS và `describe pod` báo gì? Tìm dòng `Last State` / `Reason`.
- ❓ Java trong container mặc định chỉ dùng **25%** RAM limit cho heap. Với limit 512Mi thì heap tối đa khoảng bao nhiêu? Tìm hiểu cờ `-XX:MaxRAMPercentage`.

**✍️ Trả lời:**
```

```

### TH 5.9 — Dữ liệu có sống sót không?
Tạo vài order, sau đó:
1. `kubectl delete pod <pod order-db>` → pod mới lên → order còn không?
2. `kubectl delete -f k8s/order-db.yaml` rồi `kubectl apply -f k8s/order-db.yaml` → order còn không? (Gợi ý: file này có chứa PVC không?)
3. So sánh với `docker compose down` / `down -v` ở TH3 Bài 3.

**✍️ Trả lời:**
```

```

---

## 8. Câu hỏi nộp bài

1. Deployment, ReplicaSet và Pod liên quan với nhau thế nào? Vẽ sơ đồ.
2. Vì sao dùng **label/selector** mà không trỏ Service thẳng vào tên pod?
3. Secret trong K8s mặc định chỉ được **base64** chứ không mã hoá (thử `kubectl get secret user-db-secret -o yaml`). Vậy có nên commit file `user-db.yaml` lên Git không? Thực tế người ta làm thế nào?
4. Ta vẫn đang build image **bên trong Minikube**. Nếu cluster có 10 máy thì image phải nằm ở đâu để máy nào cũng kéo được? (Chuẩn bị cho Bài 6: Registry + Cloud.)

**✍️ Trả lời:**
```
1.
2.
3.
4.
```

---

## 9. Dọn dẹp

```bash
kubectl delete namespace mcservice   # xoá SẠCH mọi thứ trong namespace (kể cả PVC!)
minikube stop                        # tắt cluster, giữ lại để lần sau start nhanh
minikube delete                      # xoá hẳn cluster
eval $(minikube docker-env -u)       # trả lệnh docker về Docker Desktop
```

---

## ✅ Checklist

- [ ] Cài Minikube, `kubectl get nodes` ra Ready
- [ ] Build 3 image vào Minikube
- [ ] Viết đủ 6 file manifest
- [ ] 6 pod Running 1/1
- [ ] Port-forward và chạy được luồng mua hàng bằng cheatsheet
- [ ] TH 5.1 – ErrImagePull
- [ ] TH 5.2 – Self-healing
- [ ] TH 5.3 – Scale
- [ ] TH 5.4 – CrashLoopBackOff
- [ ] TH 5.5 – Readiness và Liveness
- [ ] TH 5.6 – Rolling update 🚀
- [ ] TH 5.7 – Rollback
- [ ] TH 5.8 – OOMKilled
- [ ] TH 5.9 – Dữ liệu và PVC
- [ ] Câu hỏi nộp bài
