terraform {
  required_version = ">= 1.10"

  required_providers {
    # Provider = "phiên dịch viên" giữa Terraform và một nền tảng.
    # Ở đây là Docker trên máy bạn. Trên AWS thì là hashicorp/aws.
    docker = {
      source  = "kreuzwerker/docker"
      version = "~> 4.0"
    }
  }
}

# Không ghi host -> provider đọc biến môi trường DOCKER_HOST.
# Docker Desktop trên Mac để socket ở ~/.docker/run/docker.sock, nên trước khi chạy:
#   export DOCKER_HOST=$(docker context inspect -f '{{.Endpoints.docker.Host}}')
provider "docker" {}
