# Terraform cơ bản – Nó để làm gì và chạy thế nào?

> Code demo: [`infra/terraform/local-demo/`](../infra/terraform/local-demo/). Demo chạy trên **Docker của máy bạn**, miễn phí.
> Bộ Terraform AWS ([`production-design.md`](production-design.md)) dùng **đúng các lệnh và khái niệm này**, chỉ khác provider.

---

## 1. Terraform để làm gì?

### Vấn đề

Muốn có môi trường production trên AWS, bạn cần tạo khoảng **80 thứ**: VPC, 9 subnet, 3 NAT, route table, security group, EKS, IAM role, 3 RDS...

| Cách làm | Vấn đề |
|---|---|
| **Click trên AWS Console** | 3 tháng sau không ai nhớ đã click gì. Muốn tạo staging giống prod thì phải click lại từ đầu, và chắc chắn sẽ lệch. Không review được. Không biết ai đã đổi security group lúc 2 giờ sáng |
| **Viết script** `aws ec2 create-vpc ...` | Chạy lần 2 thì báo lỗi "đã tồn tại". Script chỉ biết *tạo*, không biết *sửa* hay *xoá* |
| **Terraform** ✅ | Bạn **mô tả trạng thái mong muốn**, Terraform tự tính phải tạo, sửa hay xoá gì |

Cách làm này gọi là **Infrastructure as Code (IaC)**, nghĩa là quản lý hạ tầng như quản lý code:
- **Lưu trong Git**: có lịch sử, biết ai đổi gì và vì sao.
- **Review qua PR**: người khác đọc `plan` rồi mới approve.
- **Tái tạo được**: `staging.tfvars` → `prod.tfvars`, cùng một code nên ra hai môi trường giống hệt nhau.
- **Xoá sạch được**: `terraform destroy`, không bỏ sót tài nguyên nào để rồi âm thầm tốn tiền.

### Giống cái gì bạn đã biết?

Bạn đã dùng tư duy này rồi, đó là **`docker-compose.yml`**: mô tả "tôi muốn 6 container", chạy `up`, compose tự tạo cho đủ.

| | Docker Compose | Kubernetes | Terraform |
|---|---|---|---|
| Mô tả | Container trên **1 máy** | App trên **1 cluster** | **Bất cứ thứ gì có API**: AWS, GCP, Cloudflare DNS, GitHub, Datadog... |
| Dùng để | Chạy app | Chạy app | **Tạo ra nền tảng** để app chạy trên đó (VPC, cluster, DB) |

Thực tế người ta dùng: **Terraform tạo EKS + RDS, rồi K8s/ArgoCD chạy app trên EKS đó.**
Demo này dùng Terraform để tạo container **chỉ vì muốn học miễn phí**. Ngoài đời không ai quản lý container app bằng Terraform.

---

## 2. Năm khái niệm cốt lõi

| Khái niệm | Trong demo | Ý nghĩa |
|---|---|---|
| **Provider** | `kreuzwerker/docker` | "Phiên dịch viên" giữa Terraform và một nền tảng. AWS thì dùng `hashicorp/aws` |
| **Resource** | `resource "docker_container" "user_db"` | Thứ Terraform **tạo ra và quản lý** (tạo, sửa, xoá) |
| **Data source** | `data "docker_image" "userservice"` | Thứ **đã có sẵn**, Terraform chỉ **đọc**, không tạo mà cũng không xoá |
| **Variable / Output** | `var.userservice_port`, `output "userservice_url"` | Tham số đầu vào / kết quả in ra |
| **State** | file `terraform.tfstate` | **Trí nhớ** của Terraform: "tôi đã tạo những gì, id là gì" |

### State: khái niệm quan trọng nhất

```
  Code (.tf)                     State (.tfstate)                 Thực tế (Docker / AWS)
  "tôi MUỐN có gì"               "lần trước tôi ĐÃ TẠO gì"        "hiện ĐANG CÓ gì"
        │                                │                                 │
        └────────────────┬───────────────┴─────────────────┬───────────────┘
                         ▼                                 ▼
                  terraform plan = so sánh cả 3 → liệt kê việc cần làm
```

Không có state thì Terraform không biết container `abc123` là **do nó tạo**, nên không sửa hay xoá được.
Mất state = Terraform "quên" toàn bộ hạ tầng. Đó là lý do bộ AWS lưu state trên S3 có versioning, mã hoá và `prevent_destroy`.

### Đồ thị phụ thuộc

Terraform tự tính **thứ tự tạo** từ các tham chiếu:

```
docker_image.postgres ──┐
docker_network.this ────┼──▶ docker_container.user_db ──(depends_on)──▶ docker_container.userservice
docker_volume.user_db ──┘                                                  ▲
                              data.docker_image.userservice ───────────────┘
```

Những tài nguyên không phụ thuộc nhau thì **được tạo song song** (image, network, volume cùng lúc).
`userservice` không tham chiếu tới `user_db`, nên phải khai báo `depends_on` bằng tay. Đây chính là `depends_on` của compose.

---

## 3. Vòng đời: 5 lệnh cần thuộc

```
terraform init      Tải provider (và module). Chạy 1 lần, hoặc khi đổi provider/backend
terraform plan      XEM TRƯỚC: sẽ tạo (+) / sửa (~) / xoá (-) / thay mới (-/+) những gì. KHÔNG thay đổi gì cả
terraform apply     Thực hiện. Nếu không truyền file plan thì nó chạy plan rồi hỏi "yes?"
terraform destroy   Xoá mọi thứ Terraform đã tạo
terraform state ... Xem trí nhớ: state list / state show <tài nguyên>
```

Ký hiệu trong plan (**phải đọc kỹ trước khi apply**):

| Ký hiệu | Nghĩa | Mức nguy hiểm |
|---|---|---|
| `+` create | Tạo mới | Thấp |
| `~` update in-place | Sửa tại chỗ | Trung bình |
| `-/+` replace | **Xoá rồi tạo lại** | ⚠️ **Cao**: với DB là **mất dữ liệu** |
| `-` destroy | Xoá | ⚠️ Cao |

> 💡 Đi làm, câu hỏi reviewer đặt ra đầu tiên khi đọc plan là: **"có dòng `-/+` hay `destroy` nào không? Có đụng vào database không?"**

---

## 4. Những gì đã chạy thử (kết quả thật)

```bash
cd infra/terraform/local-demo
export DOCKER_HOST=$(docker context inspect -f '{{.Endpoints.docker.Host}}')   # xem mục 6
terraform init
terraform plan -out=demo.tfplan
terraform apply demo.tfplan
```

| Bước | Kết quả |
|---|---|
| `plan` | `Plan: 5 to add, 0 to change, 0 to destroy.` |
| `apply` | image, volume, network tạo **song song**, sau đó đến `user_db` (chờ healthy), cuối cùng là `userservice` |
| Login `localhost:18081` | ✅ trả về token |
| **Thí nghiệm 1**: `plan` lần nữa khi không đổi gì | `No changes. Your infrastructure matches the configuration.` Chạy 100 lần vẫn ra như vậy (**idempotent**). Script thường không làm được điều này |
| **Thí nghiệm 2**: `docker rm -f mcservice-tf-userservice` (xoá tay) rồi `plan` | `docker_container.userservice will be created`. Terraform **phát hiện thực tế đã lệch khỏi code (drift)**, và `apply` tự sửa lại |

> Thí nghiệm 2 chính là lý do production cấm sửa tay trên Console. Mọi sửa tay sẽ bị lần `apply` tiếp theo **ghi đè mất**,
> hoặc tệ hơn là không ai biết có một thay đổi nằm ngoài code.

Demo **đang chạy** trên máy bạn (container `mcservice-tf-user-db`, `mcservice-tf-userservice`, port 18081).

---

## 5. Bài tập (bạn tự làm)

Mở terminal, và **luôn chạy 2 dòng này trước**:

```bash
cd ~/Documents/Project/MCService/infra/terraform/local-demo
export DOCKER_HOST=$(docker context inspect -f '{{.Endpoints.docker.Host}}')
```

### BT1 — Đọc state
```bash
terraform state list
terraform state show docker_container.user_db
terraform output
```
Sau đó mở file `terraform.tfstate` bằng editor, tìm chữ `POSTGRES_PASSWORD`.
- Biến `db_password` đã khai báo `sensitive = true`. Vậy trong **state** mật khẩu có bị ẩn không?
- Liên hệ `production-design.md` mục 4.4: vì sao bộ AWS dùng `manage_master_user_password = true` thay vì truyền mật khẩu qua biến?

### BT2 — Đổi port: "update" hay "replace"?
```bash
terraform plan -var userservice_port=28081
```
- Plan ghi `~` hay `-/+`? Tìm dòng có chữ `forces replacement`.
- Vì sao đổi port lại phải **xoá container rồi tạo lại**?
- Trên AWS, nếu bạn đổi `identifier` của RDS thì plan cũng ra `-/+`. Chuyện gì xảy ra với dữ liệu?

Sau đó `apply` thật với port 28081 và login thử. Cuối cùng trả về port cũ.

### BT3 — Đổi mật khẩu DB (nhớ TH5 của Bài 3?)
```bash
terraform apply -var db_password=newpass
```
- Container nào bị tạo lại? Volume có bị tạo lại không?
- Login còn được không? Xem `docker logs mcservice-tf-userservice`.
- Terraform báo `Apply complete!` màu xanh nhưng hệ thống lại hỏng. Vậy **Terraform đảm bảo điều gì, và KHÔNG đảm bảo điều gì?**

Sau khi làm xong: `terraform apply` (không có `-var`) để trả về như cũ.

### BT4 — Drift kiểu khác
```bash
docker stop mcservice-tf-userservice
terraform plan
```
Chỉ **stop** (không xoá) thì Terraform có phát hiện không? Plan đề xuất làm gì?

### BT5 — Chặn xoá nhầm
Thêm vào `resource "docker_volume" "user_db"`:
```hcl
  lifecycle {
    prevent_destroy = true
  }
```
Sau đó chạy `terraform destroy`. Chuyện gì xảy ra? Tìm xem bộ Terraform AWS dùng `prevent_destroy` ở đâu, và vì sao lại đặt ở đó.
(Xoá dòng này sau khi làm xong.)

### BT6 — Dọn dẹp
```bash
terraform destroy
docker ps -a | grep mcservice-tf        # còn không?
docker volume ls | grep mcservice-tf    # volume còn không? Dữ liệu còn không?
docker images | grep postgres           # image postgres còn không? Vì sao? (xem keep_locally)
```

---

## 6. Lỗi đã gặp khi chạy thử (và cách đọc lỗi)

```
Error: failed to create Docker client: Error pinging Docker server, please make sure that
unix:///var/run/docker.sock is reachable ...
```

- **Nguyên nhân:** provider mặc định tìm Docker ở `/var/run/docker.sock`, trong khi Docker Desktop trên Mac để socket ở `~/.docker/run/docker.sock`.
- **Cách tìm ra:** chạy `docker context ls` sẽ thấy dòng `desktop-linux *` trỏ tới `unix:///Users/<bạn>/.docker/run/docker.sock`.
- **Cách sửa:** `export DOCKER_HOST=...` trước khi chạy Terraform.

> Bài học chung: provider **không đoán** môi trường của bạn. Trên AWS cũng vậy. Terraform lấy credential từ `aws configure` / biến `AWS_PROFILE`.
> Sai profile là bạn `apply` nhầm vào **account khác**. Đó là lý do lệnh đầu tiên trước khi `apply` production luôn là `aws sts get-caller-identity`.

---

## 7. Từ demo đến bộ AWS

| | Demo local | Bộ AWS (`infra/terraform/infrastructure`) |
|---|---|---|
| Provider | `kreuzwerker/docker` | `hashicorp/aws` |
| Xác thực | `DOCKER_HOST` | AWS credential (`aws configure`, SSO) |
| State | file `terraform.tfstate` local | **S3** (dùng chung cả team, có lock) |
| Tham số | `-var ...` | `-var-file=envs/prod.tfvars` |
| Tài nguyên | 5 | khoảng 80 đến 100 |
| Thời gian apply | vài giây | 20 đến 30 phút (EKS, RDS khởi tạo lâu) |
| Chi phí | 0đ | ~$300 đến $1.000+/tháng |
| **Các lệnh** | `init` / `plan` / `apply` / `destroy` | **y hệt** |

Muốn chạy bộ AWS thật thì cần: tài khoản AWS, cài `aws` CLI, `aws configure`, sau đó theo mục 8 của `production-design.md`.
Nếu chỉ muốn thử cho biết, hãy apply **staging** rồi `destroy` **trong ngày** (chi phí khoảng vài đô).
Và nhớ **bật AWS Budget alert** trước khi làm bất cứ điều gì.

---

## ✍️ Trả lời

```
BT1:
BT2:
BT3:
BT4:
BT5:
BT6:
```
