# SportHub frontend

Next.js App Router, TypeScript, Tailwind CSS và shadcn/ui. Design language tham khảo v0; các workflow Auth, Owner, Discovery, Booking/Payment, Staff, Group, Transfer và Admin đã tách route và nối API thật.

`lib/api.ts` là typed adapter cho OpenAPI; access token nằm trong memory, refresh token dùng HttpOnly cookie. Snapshot prototype nằm trong `tests/fixtures` và không được import vào runtime.

Chạy `pnpm lint`, `pnpm typecheck`, `pnpm build`. Typecheck gọi `next typegen` để tạo route types trước khi kiểm tra. Khi chạy dev cùng backend demo, đặt `GATEWAY_URL=http://localhost:18080`; khi build container, rewrite gọi `http://api-gateway:8080` trong network Compose.

Các use case còn lại và quy tắc tài chính chưa chốt được theo dõi tại `../docs/implementation-progress.md` và `../docs/open-decisions.md`.
