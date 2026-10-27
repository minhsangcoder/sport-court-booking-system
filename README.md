# SportHub — Nền Tảng Đặt Sân Thể Thao Trực Tuyến

Hệ thống đặt sân thể thao đa cơ sở (Sport Court Booking System) xây dựng theo kiến trúc Microservices (Spring Boot 3, PostgreSQL, Redis, RabbitMQ, MinIO) kết hợp giao diện Web hiện đại (Next.js 16, TailwindCSS, TypeScript).

---

## 🚀 Hướng Dẫn Khởi Động Nhanh Cho Developer / Thành Viên Mới

Khi clone source code từ GitHub về, thực hiện theo các bước sau để chạy và có sẵn toàn bộ tài khoản test:

### 1. Chuẩn bị môi trường (`.env`)
Mở PowerShell tại thư mục gốc của dự án và chạy script khởi tạo:
```powershell
pwsh scripts/demo/init-env.ps1
```
*(Hoặc sao chép `cp .env.example .env`). Mật khẩu demo mặc định là **`SportHub123@`**.*

### 2. Khởi động hạ tầng Docker
Đảm bảo **Docker Desktop** đang chạy, sau đó bật database và các dịch vụ middleware:
```powershell
docker compose up -d
```
*(PostgreSQL, Redis, RabbitMQ, MinIO sẽ khởi động và sẵn sàng trên các port cục bộ).*

### 3. Nạp dữ liệu tài khoản test (Tất cả Role)
Chạy script seed một chạm để nạp toàn bộ 11 tài khoản kèm phân quyền:
```powershell
pwsh scripts/seed-accounts.ps1
```

### 4. Khởi động Backend & Frontend

#### Khởi động Backend Services:
```powershell
pwsh scripts/demo/start.ps1 -Rebuild
```
Script dừng các JAR của đúng dự án, build lại toàn bộ backend rồi chạy các dịch vụ tại `http://localhost:18080`. Cần Java 21 và Maven trong `PATH`. Các bài kiểm thử được chạy riêng; `-Rebuild` chỉ đóng gói bản chạy. Không xóa dữ liệu; Flyway áp dụng migration bổ sung khi khởi động. Sao lưu database trước khi nâng bản có migration. Những lần sau chưa sửa source, dùng `pwsh scripts/demo/start.ps1`; script sẽ báo nếu JAR cũ hơn source hoặc tiến trình đang chạy chưa nạp JAR mới.

#### Khởi động Frontend:
```powershell
pnpm --dir frontend install
pwsh scripts/demo/start-frontend.ps1 -Rebuild
```
Mở trình duyệt truy cập: **[http://localhost:3001](http://localhost:3001)** hoặc trang đăng nhập **[http://localhost:3001/login](http://localhost:3001/login)**.

Script dừng bản standalone cũ của đúng dự án, build bằng webpack và chạy output standalone cùng ảnh/static đúng cấu hình Next.js hiện tại. Nếu đang dùng `pnpm dev`, dừng bằng `Ctrl+C` trước. Lần sau chưa sửa source, dùng `pwsh scripts/demo/start-frontend.ps1`. Dùng `pnpm --dir frontend dev -p 3001` khi lập trình và cần tự tải lại source; bản production tránh thời gian biên dịch trang trong lúc demo. Chỉ cần `pnpm install` lần đầu hoặc khi lockfile thay đổi. Không chạy lại lệnh seed khi chỉ nâng phiên bản ứng dụng.

---

## 👥 Danh Sách Tài Khoản Test (Tất Cả Các Role)

### Chức năng mới và cấu hình môi trường

Proposal hiện hành được chỉ định tại `openspec/CURRENT_CHANGE.md`; không ghép yêu cầu từ các change cũ.

- Chat mở 50 tin mới nhất; nút tải tin cũ dùng phân trang riêng. Polling chỉ đọc tin mới.
- Worker hết hạn lọc phiên cần xử lý trước giới hạn 100; tranh chấp có người đến đúng giờ được giữ đến hạn nghiệp vụ.
- `SPORTHUB_HOLD_PROTECTION_ENABLED=true` bật cooldown/anti-regrab; cần Redis hoạt động. Quota một lượt giữ vẫn được kiểm tra ở Booking.
- `SPORTHUB_BANK_PROFILE_ENFORCEMENT=true` bật khóa đổi ngân hàng theo booking/nghĩa vụ. Khai lần đầu vẫn được phép; cần `PAYMENT_SECRET_KEYS` tại backend để lưu ngân hàng mã hóa. Giữ nguyên khóa qua các lần khởi động, không đưa vào Git hoặc frontend.
- Đặt sân mới dùng SePay: `SPORTHUB_MONEY_MODE=REAL`, `SPORTHUB_COLLECTION_PROVIDER=SEPAY`, `SEPAY_COLLECTION_CREATE_ENABLED=true`. Owner cấu hình tài khoản cơ sở và webhook tại **Kênh nhận tiền**; chỉ webhook hợp lệ, đủ tiền mới xác nhận booking. P2P cần `SPORTHUB_PEER_RESALE_FACILITIES=<UUID cơ sở đã sẵn sàng>` (hoặc `*`), B trả 100% P trực tiếp A bằng QR SePay, không có phí Owner. Không dùng booking DEMO làm chứng từ thu thật. Xem [hướng dẫn SePay](docs/sepay-payments.md).
- Giờ đặt tự chọn cần Owner công bố đủ bảng giá HOURLY và bật chính sách giờ tự chọn tại cơ sở. Không tự chuyển đổi đơn giá của dữ liệu cũ.

Sau khi sửa source hoặc cấu hình, chạy lại `pwsh scripts/demo/start.ps1 -Rebuild` và `pwsh scripts/demo/start-frontend.ps1 -Rebuild`. Không seed lại database để cập nhật ứng dụng.

Tên thư mục `scripts/demo` là tên launcher local, không quyết định chế độ tiền. `SPORTHUB_MONEY_MODE` quyết định chế độ; các giao dịch DEMO cũ không tự chuyển thành REAL khi đổi cấu hình.

> **Mật khẩu dùng chung cho tất cả tài khoản bên dưới:**  
> 🔑 **`SportHub123@`**

| Vai trò (Role) | Email đăng nhập | Tên người dùng | Quyền hạn & Chức năng | Trang kiểm tra |
|:---|:---|:---|:---|:---|
| **ADMIN** | `admin@sporthub.local` | Quản trị demo | Phê duyệt hồ sơ chủ sân, quản lý tài khoản & danh mục | `/admin` |
| **OWNER** | `owner@sporthub.local` | Chủ cơ sở demo | Quản lý sân, cài đặt giờ mở cửa, bảng giá & nhân viên | `/owner` |
| **STAFF** | `staff@sporthub.local` | Nhân viên demo | Xem lịch đặt, tạo đặt sân tại quầy, check-in, bảo trì | `/staff` |
| **CUSTOMER** *(Chính)* | `customer@sporthub.local` | Khách hàng demo | Đặt sân, giữ chỗ, thanh toán demo, tạo nhóm chia tiền | `/customer/bookings` |
| **CUSTOMER** *(Phụ)* | `customer2@sporthub.local` | Khách hàng thứ hai | Tham gia nhóm chia tiền, nhận chuyển nhượng booking | `/customer/bookings` |
| **CUSTOMER** | `demo.anh@sporthub.local` | Nguyễn Minh Anh | Thành viên đặt sân / nhóm | `/customer/bookings` |
| **CUSTOMER** | `demo.huy@sporthub.local` | Trần Quốc Huy | Thành viên đặt sân / nhóm | `/customer/bookings` |
| **CUSTOMER** | `demo.linh@sporthub.local` | Lê Thùy Linh | Thành viên đặt sân / nhóm | `/customer/bookings` |
| **CUSTOMER** | `demo.trang@sporthub.local` | Phạm Thu Trang | Thành viên đặt sân / nhóm | `/customer/bookings` |
| **CUSTOMER** | `demo.quang@sporthub.local` | Vũ Minh Quang | Thành viên đặt sân / nhóm | `/customer/bookings` |
| **CUSTOMER** | `demo.mai@sporthub.local` | Đỗ Ngọc Mai | Thành viên đặt sân / nhóm | `/customer/bookings` |

---

## 🛠 Cổng Kết Nối Mặc Định (Port Mapping)

- **Frontend App:** [http://localhost:3001](http://localhost:3001)
- **API Gateway:** [http://localhost:18080](http://localhost:18080)
- **PostgreSQL:** `localhost:15432` (User: `sporthub_demo`, Database: `identity_db`, `facility_db`, `booking_db`, ...)
- **RabbitMQ Management:** [http://localhost:25672](http://localhost:25672)
- **MinIO Console:** [http://localhost:19001](http://localhost:19001)
