# SportHub target architecture

Tài liệu này là nguồn mô tả kiến trúc hiện hành sau Phase 0. Các tài liệu đồ án trong `Document/` vẫn được giữ làm nguồn yêu cầu và lịch sử phân tích; khi có mâu thuẫn về stack hoặc phạm vi triển khai, tài liệu này và root README được ưu tiên.

## Thành phần

Frontend là Next.js App Router sử dụng TypeScript, Tailwind CSS và shadcn/ui. Frontend gọi API qua client TypeScript sinh từ OpenAPI contract.

Backend mục tiêu gồm API Gateway và năm business service: identity, facility, schedule, booking và payment. Transfer Service không thuộc phạm vi triển khai hiện tại.

Mỗi business service sở hữu database PostgreSQL riêng. Service không được query, join hoặc tạo foreign key vào database của service khác. Cross-service reference dùng UUID logic và được đồng bộ qua REST hoặc domain event.

## Giao tiếp và hạ tầng

- REST cho giao tiếp đồng bộ.
- RabbitMQ cho domain event bất đồng bộ.
- Redis cho cache, rate limit và coordination ngắn hạn; Redis không phải system of record.
- MinIO cho object storage tương thích S3.
- Mailpit cho email local development.
- Docker Compose cho môi trường local.

Phase hiện tại không đưa Kubernetes, Elasticsearch hoặc gRPC vào target architecture.

## Authentication target

- Access token JWT: 15 phút.
- Refresh token: 7 ngày.
- Refresh token lưu trong HttpOnly cookie.
- Access token chỉ giữ trong memory của frontend.
- Email verification gửi vào Mailpit khi chạy local.

Đây là target contract, chưa phải hành vi đã hoàn chỉnh trong source hiện tại.

## Contract ownership

OpenAPI service contract nằm tại `contracts/openapi/v1`. Shared transport schemas nằm trong `contracts/openapi/v1/components`; database entity không phải API contract. Breaking API change tạo major directory mới, ví dụ `v2`. Backward-compatible change tiếp tục trong `v1` và tăng `info.version`.
