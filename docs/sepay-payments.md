# Thanh toán SePay hiện hành

Quyết định 27/10/2026: SePay cho cả tiền đặt sân và chuyển nhượng. Chỉ proposal tại `openspec/CURRENT_CHANGE.md` là nguồn yêu cầu. Không tạo mới luồng phí cơ sở/payOS/DEMO cũ.

## Hai luồng tiền

- Đặt sân/đóng góp nhóm: khách chuyển vào tài khoản cơ sở. QR do server tạo theo số tiền và mã `SC` riêng. Webhook SePay đã xác thực, khớp tài khoản/ngân hàng/mã đơn, đủ tiền mới phát chứng từ để Booking xác nhận. Thiếu tiền chưa được check-in; trạng thái `CHECKED_IN` giữ nguyên.
- Mua chuyển nhượng: B trả 100% giá P trực tiếp vào ngân hàng A qua QR SePay. B gửi ảnh + mã giao dịch, A xác nhận nhận đủ tiền mới bàn giao; không có phí cơ sở, không có khoản Owner trả A. Owner chỉ phân xử quyền sân khi tranh chấp. Webhook thu của cơ sở không bàn giao P2P.

## Owner cấu hình

Customer không cần khai tài khoản ngân hàng để đặt sân hoặc mua lại lượt đặt. Chỉ người bán cần khai trước khi đăng chuyển nhượng: menu tài khoản → **Ngân hàng nhận tiền chuyển nhượng** (`/customer/profile/bank`), hoặc khai ngay tại form đăng chuyển nhượng. Tài khoản cá nhân này khác tài khoản nhận tiền đặt sân của cơ sở do Owner cấu hình dưới đây.

1. Kết nối tài khoản ngân hàng thật của cơ sở trong **SePay Live**.
2. Vào SportHub → Cơ sở của tôi → cơ sở tương ứng → **Kênh nhận tiền**. Chọn BIN, nhập STK, tên người nhận và khóa webhook riêng ít nhất 32 ký tự. Dùng cùng khóa tại SePay; không gửi khóa vào chat hoặc đưa vào Git. API chỉ trả STK che bớt, không trả khóa.
3. Đặt `PAYMENT_WEBHOOK_ORIGIN` thành URL HTTPS công khai dẫn tới payment-service/Gateway (ví dụ Cloudflare Tunnel), rồi khởi động lại payment-service. SportHub vẫn có thể chạy ở `APP_PUBLIC_URL=http://localhost:3001`; nếu không đặt URL webhook riêng, hệ thống dùng URL ứng dụng. Sao chép URL webhook hiển thị sau khi lưu kênh. SePay không thể gửi đến `localhost` trên máy phát triển.
4. Trong SePay, tạo webhook **Có tiền vào**, đúng tài khoản ngân hàng, xác thực **API Key**. Nếu lọc theo mã thanh toán thì chọn tiền tố `SC`. SePay gửi header `Authorization: Apikey <khóa đã cấu hình>`.
5. Chọn JSON làm định dạng webhook. Cơ sở cần chính sách vận hành/hoàn hợp lệ hiện hành trước khi nhận đặt sân. Không dùng webhook/khóa Sandbox để chứng nhận tiền REAL.
6. Đặt một lượt mới để lấy QR thật; giao dịch DEMO đã tạo trước đây chỉ giữ lịch sử/quyền sử dụng cũ, không biến thành chứng từ thật. Booking đã trả tiền không có thao tác Customer tự hủy; vẫn cho giải phóng giữ chỗ chưa thanh toán.

`SPORTHUB_MONEY_MODE=REAL`, `SPORTHUB_COLLECTION_PROVIDER=SEPAY`, `SEPAY_COLLECTION_CREATE_ENABLED=true`. P2P chỉ bật cho facility allowlist `SPORTHUB_PEER_RESALE_FACILITIES`. Cần giữ nguyên `PAYMENT_SECRET_KEYS` qua các lần restart để giải mã lịch sử. Tên launcher `scripts/demo` không chọn chế độ DEMO.

## Khi giao dịch có vấn đề

Cloudflare Quick Tunnel dùng cho thử local: `cloudflared tunnel --url http://127.0.0.1:18085 --protocol http2 --edge-ip-version 4 --no-autoupdate`. Điền origin `https://…trycloudflare.com` được in ra vào `PAYMENT_WEBHOOK_ORIGIN`, rồi khởi động lại payment-service. Địa chỉ đầy đủ là origin đó + `/api/v1/payments/providers/sepay/{channelId}/webhook`; lấy channelId từ kênh đã lưu trong SportHub, không dùng facilityId. Quick Tunnel đổi hostname khi tạo lại và cần giữ tiến trình chạy; URL cố định cần tunnel có tên miền. Không bật xác thực email tương tác trước webhook; webhook dùng API Key đã cấu hình. Xem [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).

Webhook được ghi bền vững trước ACK `{ "success": true }`. SePay ID và tham chiếu ngân hàng chống ghi trùng. Sai khóa không được ghi tiền; sai tài khoản/ngân hàng hoặc dữ kiện xung đột đưa phiên vào REVIEW. Không ghép mơ hồ khi có nhiều mã đơn. Tham chiếu ngân hàng rỗng dùng SePay ID ổn định. Giao dịch đi hoặc không khớp đơn được lưu dấu, không cấp quyền.

Tiền chưa đủ, đến sau hạn hoặc vượt số phải trả không biến thành doanh thu đặt sân thêm. Cơ chế khoản thu chưa áp dụng và hoàn vận hành hiện có giữ nghĩa vụ đối soát; không tự khôi phục một sân đã nhả. QR có thể vẫn dùng được ở ngân hàng sau khi hết hạn trong SportHub, vì vậy không chuyển theo QR cũ.

Các bảng/class mang tên `payos_*`/`Payos*` là sổ kênh/phiên/chứng từ cũ được dùng chung để giữ nguyên khóa ngoại và lịch sử. Cột provider phân biệt `SEPAY`/`PAYOS`; worker payOS chỉ xử lý đơn payOS đã có. Không gọi payOS để tạo đơn SePay, không gọi bộ thu cơ sở để xử lý tiền P2P.

Kiểm thử dùng hạ tầng/ảnh và webhook fixture riêng. Chưa có xác nhận chuyển tiền ngân hàng thật. Sau khi Owner cấu hình kênh Live và URL công khai, cần nghiệm thu một giao dịch nhỏ do người dùng chủ động thực hiện.

Nguồn tích hợp: [QR động SePay](https://docs.sepay.vn/tao-qr-code-vietqr-dong.html), [payload, ACK và chống trùng webhook](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook), [phân biệt Sandbox](https://developer.sepay.vn/vi/sepay-webhooks/test-mode/bat-dau-nhanh).
