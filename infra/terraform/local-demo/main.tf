# Mô tả TRẠNG THÁI MONG MUỐN: "tôi muốn có 1 network, 1 volume, 1 Postgres, 1 UserService".
# Terraform tự tìm ra phải tạo / sửa / xoá gì để thực tế khớp với mô tả này.
# So sánh: đây chính là docker-compose.yml của Bài 3, viết bằng ngôn ngữ của Terraform.

resource "docker_network" "this" {
  name = "${var.project}-net"
}

resource "docker_volume" "user_db" {
  name = "${var.project}-user-db-data"
}

resource "docker_image" "postgres" {
  name         = "postgres:16-alpine"
  keep_locally = true # destroy không xoá image khỏi máy
}

resource "docker_container" "user_db" {
  name  = "${var.project}-user-db"
  image = docker_image.postgres.image_id # tham chiếu -> Terraform biết phải có image TRƯỚC

  env = [
    "POSTGRES_DB=user_db",
    "POSTGRES_USER=app",
    "POSTGRES_PASSWORD=${var.db_password}",
  ]

  networks_advanced {
    name    = docker_network.this.id
    aliases = ["user-db"] # tên DNS trong network, giống tên service trong compose
  }

  volumes {
    volume_name    = docker_volume.user_db.name
    container_path = "/var/lib/postgresql/data"
  }

  healthcheck {
    test     = ["CMD-SHELL", "pg_isready -U app -d user_db"]
    interval = "5s"
    retries  = 10
  }

  # Chờ healthcheck báo healthy rồi mới coi là tạo xong (giống condition: service_healthy)
  wait         = true
  wait_timeout = 120
}

# data = ĐỌC một thứ đã tồn tại (không tạo mới). Image này bạn đã build ở Bài 3.
data "docker_image" "userservice" {
  name = var.userservice_image
}

resource "docker_container" "userservice" {
  name  = "${var.project}-userservice"
  image = data.docker_image.userservice.id

  env = [
    "SPRING_DATASOURCE_URL=jdbc:postgresql://user-db:5432/user_db",
    "SPRING_DATASOURCE_USERNAME=app",
    "SPRING_DATASOURCE_PASSWORD=${var.db_password}",
  ]

  ports {
    internal = 8081
    external = var.userservice_port
  }

  networks_advanced {
    name = docker_network.this.id
  }

  # Không có tham chiếu nào tới user_db -> phải khai báo thứ tự bằng tay
  depends_on = [docker_container.user_db]
}
