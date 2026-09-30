# OpenAPI contracts

OpenAPI là source of truth cho HTTP API của SportHub.

## Convention

- Major version nằm trong directory `v1`, `v2`.
- File service dùng lower-case kebab-case.
- Schema dùng PascalCase; operationId dùng lowerCamelCase ổn định.
- URL API dùng `/api/v1` khi service contract được bổ sung.
- Breaking change tạo major directory mới; additive change giữ nguyên major và tăng `info.version`.

Service contracts dự kiến: `identity.yaml`, `facility.yaml`, `schedule.yaml`, `booking.yaml`, `payment.yaml`. Phase 0 chỉ tạo common transport schemas, không tự đặt endpoint nghiệp vụ.

Script `scripts/openapi/openapi.ps1` validate contract trước khi sinh TypeScript Fetch client cho frontend hoặc Spring interfaces/models cho backend. Generated output được ignore và mặc định regenerate ở local/CI.
