# Proposal

## Why

SportHub hiện buộc booking khớp các slot vận hành, nên chưa đặt được 15:30–16:30 khi bảng giá đổi lúc 16:00; luồng chuyển nhượng cũng chưa đáp ứng B trả trực tiếp A. Cần đồng thời chống giữ chỗ/phá giao dịch, khóa đổi ngân hàng khi còn trách nhiệm và xử lý các đường tải chậm bằng số đo thực tế.

## What Changes

- Quyết định mới nhất 28/10/2026 (mục 27): thêm chọn giờ bắt đầu/kết thúc theo bước 30 phút (ví dụ 14:30–15:30), hiển thị các khoảng trống liên tục và cập nhật mỗi 5 giây. Người dùng xác nhận tính tiền theo tỷ lệ thời lượng từ giá/khung hiện có cho báo giá mới, không cần chuyển dữ liệu giá cũ sang HOURLY; giữ nguyên tiền/snapshot booking đã tạo. Tính đúng thời lượng gốc của từng khoảng mở cửa, cộng phân số chính xác rồi làm tròn VND một lần. Quyết định này thay điều kiện phải công bố HOURLY trước khi đặt linh hoạt bằng giá cũ.
- Mục 27: nhóm được tạo trước giờ chơi ít nhất 15 phút; hạn góp tiền là thời điểm sớm hơn giữa tạo nhóm + 30 phút và giờ chơi - 5 phút. Người tổ chức có thể trả các phần chưa đóng để đủ tiền nhận sân qua các khoản đóng góp hiện có. Cho thay QR SePay của thành viên chưa chuyển tiền bằng lệnh trả thay; không thay đơn đã nhận một phần tiền hoặc đã đủ tiền, không đổi phân bổ sau khi mở thanh toán. Mọi chuyển trùng được ghi nhận/đối soát, chỉ áp dụng một lần vào nghĩa vụ.

- Quyết định 28/10/2026 (mục 26): ẩn chuỗi token dài dưới QR booking và điểm danh nhóm, giữ QR và mã thủ công 10 ký tự chỉ cho Customer hiện tại. Xóa hẳn controller/service hủy theo chính sách chỉ còn trả lỗi, hợp đồng API/DTO mồ côi và phần tạo/quote/edit/acquire chuyển nhượng theo phí cũ không có caller production. Endpoint hủy đã bỏ trả404. Chuyển kiểm thử đối soát sang fixture lịch sử; xóa E2E khởi tạo nghiệp vụ đã bỏ. Giữ kiểm tra quyền/tiền, scanner, lịch sử và nghĩa vụ đang xử lý; không xóa dữ liệu hoặc migration.

- Làm rõ 28/10/2026 (mục 25): Owner không đặt sân cá nhân tại cơ sở mình sở hữu. Server kiểm ownerId của cơ sở khi giữ chỗ/replay hold và tạo booking, gồm nhóm và nháp cũ; quầy không được liên kết chính chủ cơ sở làm Customer. Owner vẫn đặt cá nhân ở cơ sở khác, đặt tại quầy cho Customer khác/khách vãng lai và check-in khách đúng quyền, đủ tiền, đúng giờ. Menu tài khoản desktop/mobile và quản lý cơ sở có lối vào rõ ràng cho Lịch đặt & Check-in khách và Đặt tại quầy. Không thay người sở hữu, hủy hoặc sửa tiền của booking đã tạo trước thay đổi.

- Quyết định 28/10/2026 (mục 24) thay mọi mô tả hủy theo chính sách cũ: Owner/Staff không được hủy booking của Customer để tạo khoản hoàn. Cả REAL/DEMO, cá nhân/nhóm/quầy đều không tạo quote hoặc lệnh hủy theo chính sách mới; endpoint cũ đã xóa theo mục26 (trả404), giữ nghĩa vụ đã ghi nhận và hoàn thu lỗi. Giữ mã check-in 10 ký tự, chỉ Customer đang sở hữu được đọc; Owner/Staff nhập mã hoặc quét QR do khách cung cấp, không lấy mã/QR từ API vận hành. Khách vãng lai được xác nhận có mặt bằng lệnh vận hành riêng, chỉ đúng quyền cơ sở, đủ tiền và trong cửa sổ check-in; không cấp mã cho Staff, không gán Staff làm chủ booking. Giữ CHECKED_IN và không sửa dữ liệu lịch sử.

- Quyết định mới nhất ngày 2026-10-27 về thanh toán: **SePay cho cả đặt sân ban đầu và chuyển nhượng**. Đặt sân: khách trả vào tài khoản cơ sở, server chỉ ghi nhận đủ tiền từ webhook SePay đã xác thực và khớp tài khoản/mã đơn; không dùng nút mô phỏng. Chuyển nhượng: B trả 100% P trực tiếp A qua QR SePay, B gửi chứng từ và A xác nhận nhận tiền; không có phí cơ sở/SportHub. Gỡ chức năng Customer tự hủy booking đã thanh toán (kể cả hủy không hoàn); vẫn cho hủy giữ chỗ chưa trả tiền. Không tạo mới/đổi giá/mua thêm qua protocol phí cũ, kể cả booking DEMO. Lịch sử, nghĩa vụ đã chốt và phiên giao dịch cũ đã mở được tiếp tục xử lý, không đổi chứng từ DEMO thành REAL. Quyết định này thay thế các mô tả payOS tạo đơn mới và DEMO tạo chuyển nhượng mới bên dưới/trước đây.

- Thanh toán nhóm: hiển thị mã mời ngay cạnh tiến độ thanh toán; tự cập nhật thành viên mỗi 5 giây. Người tổ chức chốt chia đều khi đủ người hoặc dùng chia tùy chỉnh, không tự chia khi có người tham gia. Bỏ panel hoàn dành cho người mua chuyển nhượng trên trang nhóm. Không đổi phân bổ sau khi bắt đầu thanh toán.

- Quyết định mới nhất ngày 2026-10-27: bỏ chính sách hoàn riêng cho người mua lại, cả REAL/P2P và DEMO. Không tạo báo giá/nghĩa vụ hoàn mới sau chuyển nhượng, kể cả Owner/Staff gửi thay; giữ lịch sử và khoản hoàn đã ghi nhận. Gỡ khối thông tin phụ trong ảnh Customer, form hoàn của người mua lại và quảng bá tỷ lệ hoàn sang nhượng. Luồng A nhận P, bàn giao B và check-in đủ tiền không đổi.

- Đặt một khoảng liên tục theo bước 30 phút, trong giờ mở cửa, từ 60 đến 240 phút/lượt; cộng giá từng đoạn theo đơn giá/giờ và làm tròn tổng một lần. Ví dụ 100k/h từ 15–16 và 200k/h từ 16–17 cho 15:30–16:30 là 150k. Cảnh báo khoảng trống mới dưới 60 phút và gợi ý giờ khác, không chặn ví dụ này.
- **BREAKING — giao dịch REAL mới:** chỉ booking gốc REAL đã đủ tiền hợp lệ được mua lại theo protocol `PEER_RESALE_V1`, mở chat A–B và QR SePay trả đúng P vào ngân hàng A đã khai. B nộp ảnh biên lai+mã giao dịch; A xác nhận nhận tiền mới khởi động bàn giao. Booking DEMO hiện có giữ lịch sử và quyền sử dụng cũ; không tạo chuyển nhượng mới hoặc dùng khoản thu mô phỏng làm chứng từ REAL. Không yêu cầu webhook SePay của A hoặc gọi lời khai là xác thực ngân hàng.
- Bỏ phí chuyển nhượng x%, khoản Owner trả A và doanh thu chuyển nhượng của cơ sở/SportHub đối với protocol mới. SePay thu tiền đặt sân ban đầu; payOS chỉ tiếp tục đối soát đơn cũ. Giữ hoàn vận hành ngoài phạm vi chuyển nhượng; không giữ quyền hoàn riêng của người mua lại. Lịch sử, đơn đang xử lý và công nợ cũ tiếp tục đúng protocol cũ.
- Mỗi booking chỉ được chuyển quyền do chuyển nhượng thành công một lần, kể cả phân xử cho B. B mua lại không được đăng bán tiếp; không chia nhỏ hoặc đổi sân/khung giờ khi chuyển nhượng.
- Phiên mua giữ tối đa 5 phút; không nộp khai thanh toán đúng hạn thì mở lại listing, chat vẫn sử dụng được. Khi đã khai thanh toán hợp lệ, chặn sửa/hủy/check-in trong lúc chờ xác nhận hoặc tranh chấp. B được khiếu nại nếu A không xác nhận; A báo chưa nhận sẽ mở tranh chấp.
- Owner đúng cơ sở chỉ phân xử QUYỀN SỬ DỤNG SÂN từ bằng chứng hai bên; không ra lệnh hoàn P2P, xác định khoản Owner phải trả B hoặc xác nhận Owner đã chi tiền. Đến giờ chơi, chỉ khi cả hai không có mặt và chưa có quyết định bàn giao thì mặc định A giữ quyền. Tiền A–B do hai bên tự xử lý/xác nhận riêng; không tự coi B nói dối hoặc đã được hoàn.
- Nếu ít nhất một bên có mặt đúng giờ, Owner được bàn giao B phần thời gian còn lại trước endsAt khi chưa có quyết định cuối/check-in; giữ lịch gốc, không tự bù thêm thời gian.
- Chống chiếm giữ: tối đa 1 claim đang hoạt động/user trên toàn hệ thống cho booking online + mua chuyển nhượng; đủ 3 lần bỏ/hết hold liên tiếp trong 1 giờ nghỉ 30 phút, lần 5 trong ngày nghỉ 24 giờ; chặn giữ lại phần giờ giao nhau trên cùng sân trong 15 phút. Staff đặt tại quầy dùng kiểm soát riêng theo quyền cơ sở.
- Sau kết luận cố ý khai chuyển tiền giả của Owner: mỗi vụ 1 strike và cấm chợ 30 ngày; đủ 3 strike cấm chợ vĩnh viễn. Không áp strike tự động cho thiếu chứng cứ/không đến sân. Giới hạn 5 yêu cầu/phút/user cho các lệnh giữ chỗ; chỉ tài khoản ACTIVE đã xác thực email hoặc điện thoại được tham gia.
- **BREAKING — thông tin nhận tiền cá nhân:** chặn sửa, thêm tài khoản thay thế, xóa hoặc đổi tài khoản đang dùng khi còn hold/booking chưa kết thúc, listing/phiên chuyển nhượng, tranh chấp chưa có quyết định cuối về quyền sân hoặc khoản trả/hoàn đã xác nhận nhưng chưa xong. Quyết định cuối về quyền sân gỡ riêng nguồn khóa tranh chấp, kể cả case tiền còn lưu để hai bên xử lý; không tự đóng case hoặc bỏ các nguồn khóa khác. Vẫn cho khai ngân hàng lần đầu nếu chưa có; khóa được kiểm tại server, kể cả yêu cầu đồng thời. Lịch sử đã kết thúc không khóa vĩnh viễn; Staff tạo booking hộ không bị coi là người có trách nhiệm nhận tiền. Kênh payOS của cơ sở theo quy tắc riêng.
- Tối ưu FE/BE trên cùng bản production và dữ liệu trước/sau: ưu tiên request bị tải lại ngoài phạm vi thay đổi, báo cáo REAL, lịch/quote, danh mục ngân hàng và phần giao diện nặng. Dùng công cụ đo hiện có; mục tiêu giảm ít nhất 20% ở đường chậm được xác nhận, không coi chia file là bằng chứng nhanh hơn hoặc giảm kiểm tra quyền/tiền để đạt chỉ tiêu. Tách thời gian biên dịch dev khỏi thời gian xử lý API.

- Làm rõ 27/10/2026: Customer không phải khai ngân hàng để đặt sân hoặc mua lại lượt đặt; chỉ người bán A cần tài khoản nhận tiền trước khi đăng chuyển nhượng. Menu tài khoản trên máy tính/điện thoại phải dẫn rõ tới trang khai ngân hàng, vẫn cho khai lần đầu khi đã có lịch. Tài khoản nhận tiền SePay của cơ sở do Owner cấu hình riêng; trạng thái chưa cấu hình phải mở được form lần đầu, không bị coi là lỗi dữ liệu hoặc yêu cầu ngân hàng của Customer.

- Cập nhật 28/10/2026: bỏ khối “Khoản cơ sở trả và hoàn của bạn” và bản mô phỏng tương ứng khỏi toàn bộ trang Customer (booking, nhóm, chuyển nhượng). Xóa component/caller và lệnh tải dữ liệu chỉ phục vụ khối này; không xóa nghĩa vụ, giao dịch đã ghi nhận hoặc màn hình vận hành của cơ sở.

## Capabilities

### New Capabilities

- `schedule/flexible-pricing`: giờ chọn tự do, đơn giá giờ có phiên bản, phân đoạn và cảnh báo khoảng trống.
- `booking/hold-protection`: quota chung, cooldown, anti-regrab, rate limit và strike có quyết định.
- `payments/peer-qr`: QR SePay riêng tư, khóa đổi ngân hàng theo trách nhiệm và mức xác nhận đúng bản chất.
- `transfers/conversations`: chat A–B, ảnh chứng cứ riêng tư và giới hạn chống spam.
- `transfers/settlement`: vòng đời mua trực tiếp, xác nhận và giới hạn một lần chuyển nhượng.
- `transfers/disputes`: Owner phân xử, có mặt tại cơ sở và hết hạn quyền sử dụng.
- `booking/paid-check-in`: bàn giao nguyên tử, đóng băng tranh chấp và bảo toàn CHECKED_IN.
- `booking/group-payments`: mã mời ngay tại thanh toán nhóm, cập nhật thành viên và người tổ chức chốt chia tiền.
- `booking/facility-refunds`: không tạo quyền hoàn tiền khi người mua lại tự hủy; chỉ giữ nghĩa vụ đã ghi nhận và hoàn vận hành/thu lỗi hợp lệ.
- `reporting/facility-performance`: tách tiền cơ sở khỏi giao dịch A–B/công nợ cũ, bảo toàn kết quả khi tối ưu tải báo cáo.

### Modified Capabilities

Không có main spec đã sync (`openspec list --specs --json` hiện rỗng). Các đường dẫn booking/transfer/reporting liên quan được giữ theo tổ chức của change trước; delta ở đây dùng ADDED. Phần áp dụng mới thay thế yêu cầu tương ứng của `enable-real-payments-and-vietqr-payouts` theo protocol/cutover, không sửa hoặc archive change cũ trong bước này.

## Impact

- Schedule, Booking, Transfer, Payment, Identity/Gateway và frontend chọn giờ, tài khoản ngân hàng, chat, tranh chấp, vận hành và báo cáo. Cần hợp đồng API/version mới và migration bổ sung khi apply; không đổi snapshot booking cũ.
- Tái sử dụng PostgreSQL range exclusion/court lock, outbox/inbox, Redis, MinIO, bank destination revision, kiểm tra tiền sân và phân quyền hiện có. Không thêm microservice chat, ví, escrow, OCR hoặc hệ thống duyệt ngân hàng.
- Bổ sung hợp đồng trạng thái khóa ngân hàng/tài khoản đang dùng, điều phối kích hoạt revision và kiểm thử race; frontend hiển thị lý do khóa thay vì chỉ vô hiệu hóa nút. Mở rộng benchmark/refactor đã có cho REAL và peer mới; không mở lại hoặc ghi đè change refactor cũ.
- Tích hợp dịch vụ tạo ảnh QR theo [tài liệu SePay](https://docs.sepay.vn/tao-qr-code-vietqr-dong.html); tạo QR và nhận webhook là hai chức năng khác nhau. Proposal chỉ chọn tạo QR, không tích hợp webhook để xác thực tiền A nhận.
- Toàn bộ artifact này là kế hoạch. Không sửa backend/frontend, tạo migration, chạy test tác động dữ liệu, triển khai hoặc bắt đầu apply trong bước propose.
