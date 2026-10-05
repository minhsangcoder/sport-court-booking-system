# SportHub

SportHub là đồ án tốt nghiệp xây dựng nền tảng Web tìm kiếm, đặt và quản lý sân thể thao. Các vertical slice chính đã có API, database riêng, Gateway và frontend gọi API thật; xem kết quả kiểm tra tại `docs/implementation-progress.md`.

## Trạng thái hiện tại

Đã có:

- Xác thực, session rotation/revocation, multi-role và xác minh thay đổi email/số điện thoại
- Owner quản lý cơ sở/sân, ảnh MinIO, lịch/giá, quyền Staff và kiểm duyệt cơ sở bổ sung
- Tìm sân theo khu vực/bộ môn/ngày/giờ/giá/khoảng cách bằng lịch trống thật; giữ chỗ, payment demo, booking/QR và Staff check-in/complete
- Group invitation/split/contribution/confirmation và Transfer marketplace/payment/handoff
- Admin account controls, hồ sơ kiểm duyệt, Booking/Payment monitoring, source reports và audit
- Bảy backend service, frontend production, sáu PostgreSQL database và PostgreSQL/Redis/RabbitMQ/MinIO/Mailpit trong Compose
- OpenAPI theo service; outbox/inbox, retry, DLQ và idempotency

Phần còn lại hoặc bị cô lập theo quy tắc nghiệp vụ:

- Owner application gắn với cơ sở đầu tiên và các workflow Admin mở rộng
- Refund execution, thay đổi Group sau khi đã đóng tiền và Transfer escrow release chờ quyết định nghiệp vụ trong `docs/open-decisions.md`
- Không coi tất cả use case của đồ án đã hoàn tất chỉ vì các luồng demo chính chạy được

## Kiến trúc mục tiêu

Frontend sử dụng Next.js App Router, TypeScript, Tailwind CSS và shadcn/ui. v0 là nguồn tham khảo thiết kế; workflow được tách thành route/page/tab và gọi API thật qua typed adapter. Snapshot prototype chỉ nằm trong test fixtures.

Backend sử dụng Java 21, Spring Boot 3 và Maven. Các deployable service gồm `api-gateway`, `identity-service`, `facility-service`, `schedule-service`, `booking-service`, `payment-service` và `transfer-service`. `sporthub-common` chỉ dành cho technical primitives, không phải nơi chia sẻ domain entity giữa các service.

Mỗi business service sở hữu database PostgreSQL riêng. Không tạo foreign key xuyên service; tham chiếu xuyên service dùng UUID logic. Giao tiếp đồng bộ dùng REST, giao tiếp bất đồng bộ dùng RabbitMQ domain event.

## Cấu trúc repository

```text
sport-court-booking-system/
|-- frontend/
|-- backend/
|   |-- api-gateway/
|   |-- sporthub-common/
|   |-- identity-service/
|   |-- facility-service/
|   |-- schedule-service/
|   |-- booking-service/
|   |-- payment-service/
|   `-- transfer-service/
|-- contracts/openapi/
|-- docs/
|-- Document/                  # tài liệu đồ án nguồn, được giữ nguyên
|-- infra/
|-- scripts/openapi/
|-- docker-compose.yml
|-- .env.example
`-- README.md
```

## Yêu cầu môi trường

- Docker Desktop và Docker Compose
- Node.js 24+ và pnpm 11.19.0
- Java 21
- Maven 3.9+

## Cấu hình môi trường

Sao chép `.env.example` thành `.env`, sau đó thay toàn bộ marker `__REPLACE_*__`. Không commit `.env` hoặc secret thật.

JWT target: access token 15 phút, refresh token 7 ngày, refresh token lưu trong HttpOnly cookie và access token chỉ giữ trong memory của frontend. Mailpit nhận email local tại `http://localhost:8025`.

## Chạy hạ tầng local

```powershell
Copy-Item .env.example .env
# Thay các marker trong .env trước khi chạy
docker compose config
docker compose up -d
docker compose ps
```

Compose mặc định chỉ khởi động infrastructure. Để tạo cấu hình local/demo với credentials ngẫu nhiên và chạy toàn bộ application bằng container:

```powershell
.\scripts\demo\init-env.ps1
.\scripts\demo\start-apps.ps1
.\scripts\demo\verify-migrations.ps1
.\scripts\demo\smoke-discovery.ps1
.\scripts\demo\smoke-booking-payment.ps1
```

Script build image tuần tự để dùng chung cache Maven; chỉ dừng JVM demo thuộc checkout này khi chuyển sang container. `.env` có sẵn được giữ nguyên. Mặc định demo: frontend `http://localhost:13000`, Gateway `http://localhost:18080`, Mailpit `http://localhost:18025`. `start-apps.ps1` đồng bộ public links và CORS với port frontend; `-PublicUrl` hỗ trợ một địa chỉ demo khác.

Tài khoản seed: `customer`, `customer2`, `owner`, `staff`, `admin` tại `@sporthub.local`; mật khẩu là `DEMO_PASSWORD` trong `.env`. Seed và SMS qua Mailpit chỉ bật trong local/demo. Payment demo không chuyển tiền thật; escrow/refund không tự chọn chính sách.

## Frontend

```powershell
cd frontend
pnpm install --frozen-lockfile
$env:GATEWAY_URL='http://localhost:18080'
pnpm typecheck
pnpm lint
pnpm build
pnpm dev
```

## Backend

```powershell
cd backend
mvn verify
cd ..
.\scripts\demo\start-apps.ps1 -SkipBuild
```

Test có thể dùng Testcontainers hoặc PostgreSQL test riêng. Trong worktree đã cấu hình database test, chạy từ repository root:

```powershell
$env:SPORTHUB_TEST_DB_USER='sporthub_test'
$env:SPORTHUB_TEST_DB_PASSWORD='sporthub-test-only'
.\scripts\demo\build-backend.ps1 -UseExternalTestDatabase -TestsOnly
```

Database test phải tách khỏi sáu database demo; script không xóa dữ liệu hoặc volume.

## OpenAPI workflow

Contracts Identity, Facility, Schedule, Booking/Group/Discovery, Payment, Transfer và Admin nằm tại `contracts/openapi/v1/`. Generated code được regenerate và không commit mặc định.

```powershell
.\scripts\openapi\openapi.ps1 -Action validate -Contract common
.\scripts\openapi\openapi.ps1 -Action frontend -Contract identity
.\scripts\openapi\openapi.ps1 -Action backend -Contract identity -ServiceDirectory identity-service
```

Chi tiết convention nằm trong `contracts/openapi/README.md`.

Các quyết định nghiệp vụ chưa chốt nằm trong `docs/open-decisions.md`. `Document/` giữ nguyên và là nguồn yêu cầu nghiệp vụ.
