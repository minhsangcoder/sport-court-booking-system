# Local infrastructure

Root `docker-compose.yml` cung cấp PostgreSQL, Redis, RabbitMQ, MinIO và Mailpit. PostgreSQL local dùng một server nhưng tạo năm database tách biệt theo service; không có cross-service foreign key.

Application containers hiện có được đặt trong profile `apps`. Facility và payment chưa có runnable source nên không có container giả. Transfer Service và `transfer_db` không được tạo.
