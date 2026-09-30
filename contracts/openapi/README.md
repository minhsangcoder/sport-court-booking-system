# OpenAPI contracts

OpenAPI là source of truth cho HTTP API của SportHub.

## Convention

- Major version nằm trong directory `v1`, `v2`.
- File service dùng lower-case kebab-case.
- Schema dùng PascalCase; operationId dùng lowerCamelCase ổn định.
- URL API dùng `/api/v1` khi service contract được bổ sung.
- Breaking change tạo major directory mới; additive change giữ nguyên major và tăng `info.version`.

`identity.yaml` là contract service đầu tiên và bao phủ MVP account/authentication, profile, owner application và staff binding. Các contract `facility.yaml`, `schedule.yaml`, `booking.yaml`, `payment.yaml` vẫn được bổ sung ở phase tương ứng.

Script `scripts/openapi/openapi.ps1` validate contract trước khi sinh TypeScript Fetch client cho frontend hoặc Spring interfaces/models cho backend. Generated output được ignore và mặc định regenerate ở local/CI.

Refresh token của Identity API chỉ được truyền bằng cookie `sporthub_refresh` có `HttpOnly`, thời hạn 7 ngày và `SameSite=Lax`. Local HTTP dùng `Secure=false`; mọi môi trường HTTPS phải dùng `Secure=true`. Access token JWT có thời hạn 15 phút, trả trong response body và chỉ giữ trong memory của frontend.
