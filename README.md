# SportHub

SportHub là đồ án tốt nghiệp xây dựng nền tảng Web tìm kiếm, đặt và quản lý sân thể thao. Repository đã có baseline Phase 0 và nền tảng Phase 1 cho domain-event contract, API Gateway hardening và Identity OpenAPI contract. Các nghiệp vụ xác thực, facility CRUD, booking, payment, group booking, transfer marketplace và analytics vẫn chưa được triển khai.

## Trạng thái hiện tại

Đã có:

- Giao diện prototype từ v0 sử dụng Next.js App Router, TypeScript, Tailwind CSS và shadcn/ui
- Maven multi-module Java 21/Spring Boot 3 với API Gateway, common module và skeleton cho identity, schedule, booking
- Migration ban đầu cho identity và schedule
- Docker Compose cho PostgreSQL, Redis, RabbitMQ, MinIO và Mailpit
- OpenAPI v1 common schemas và script chuẩn bị code generation
- Identity OpenAPI contract; generated code vẫn được regenerate và không commit mặc định
- Gateway header sanitization, correlation ID, CORS, Redis rate-limiter configuration và authentication seam no-op
- Domain-event envelope cùng convention retry, DLQ và idempotency

Chưa có:

- Business API hoàn chỉnh cho các use case
- `facility-service` và `payment-service` runnable
- Service-specific OpenAPI contracts ngoài Identity
- Tích hợp thật giữa frontend và backend
- Transfer Service

## Kiến trúc mục tiêu

Frontend sử dụng Next.js App Router, TypeScript, Tailwind CSS và shadcn/ui. UI v0 hiện có được giữ nguyên. Việc gọi API sau này phải đi qua TypeScript client sinh từ OpenAPI.

Backend sử dụng Java 21, Spring Boot 3 và Maven. Các deployable service mục tiêu gồm `api-gateway`, `identity-service`, `facility-service`, `schedule-service`, `booking-service` và `payment-service`. `sporthub-common` chỉ dành cho technical primitives, không phải nơi chia sẻ domain entity giữa các service.

Mỗi business service sở hữu database PostgreSQL riêng. Không tạo foreign key xuyên service; tham chiếu xuyên service dùng UUID logic. Giao tiếp đồng bộ dùng REST, giao tiếp bất đồng bộ dùng RabbitMQ domain event.

## Cấu trúc repository

```text
sport-court-booking-system/
|-- frontend/
|-- backend/
|   |-- api-gateway/
|   |-- sporthub-common/
|   |-- identity-service/
|   |-- facility-service/      # planned placeholder
|   |-- schedule-service/
|   |-- booking-service/
|   `-- payment-service/       # planned placeholder
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

Compose mặc định chỉ khởi động infrastructure. Các application container hiện có nằm trong profile `apps`:

```powershell
docker compose --profile apps up --build
```

## Frontend

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm typecheck
pnpm lint
pnpm build
pnpm dev
```

## Backend

```powershell
cd backend
mvn verify
mvn -pl identity-service -am spring-boot:run
```

Các module hiện tại là foundation/skeleton; không được hiểu là các nghiệp vụ đã hoàn chỉnh.

## OpenAPI workflow

Shared schemas nằm tại `contracts/openapi/v1/components/schemas.yaml`. Identity contract nằm tại `contracts/openapi/v1/identity.yaml`; facility, schedule, booking và payment contract sẽ được thêm ở các phase tương ứng.

```powershell
.\scripts\openapi\openapi.ps1 -Action validate -Contract common
.\scripts\openapi\openapi.ps1 -Action frontend -Contract identity
.\scripts\openapi\openapi.ps1 -Action backend -Contract identity -ServiceDirectory identity-service
```

Chi tiết convention nằm trong `contracts/openapi/README.md`.

Các quyết định nghiệp vụ/kỹ thuật chưa chốt nằm trong `docs/open-decisions.md`. Phase 1 không triển khai controller/service Identity; contract `identity.yaml` là đầu vào cho Phase 2 sau khi các quyết định liên quan được xác nhận.
