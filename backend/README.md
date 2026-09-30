# Backend

Backend sử dụng Java 21, Spring Boot 3 và Maven multi-module. Các module hiện có là Phase 0 foundation/skeleton; không phải business implementation hoàn chỉnh.

`facility-service` và `payment-service` hiện chỉ là planned boundary. `sporthub-common` phải giữ ở phạm vi technical primitives và không được chứa shared business/domain ownership.
