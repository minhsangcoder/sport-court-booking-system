# Spec Delta

## Purpose

Cung cấp QR SePay để B chuyển đúng giá vào tài khoản A đã chấp nhận, khóa thay đổi thông tin nhận tiền khi còn trách nhiệm và giữ mức xác nhận phù hợp luồng hai bên tự xác nhận.

## ADDED Requirements

### Requirement: A seller bank destination is required before publishing

A SHALL có tài khoản ngân hàng cá nhân đang dùng đã được kích hoạt trước quote/publish listing mới. Revision và consent dùng chia sẻ dữ liệu cần thiết cho buyer/SePay SHALL được ghi nhận; không dùng revision staging/lịch sử khác để lách khóa hoặc mặc định ngân hàng Owner/Staff.

#### Scenario: Seller bypasses the profile requirement
- **WHEN** A gọi API publish thiếu bank revision hoặc dùng revision của người khác
- **THEN** không đăng tin; hướng dẫn A khai/chọn tài khoản của mình

#### Scenario: Customer finds registration only when needed to sell
- **WHEN** Customer mở menu tài khoản trên desktop/mobile hoặc chuẩn bị đăng chuyển nhượng
- **THEN** có đường dẫn rõ tới form khai ngân hàng; chỉ seller phải khai trước publish, không buộc Customer khai để đặt sân hoặc buyer mua lại, vẫn cho khai lần đầu khi đang có lịch

### Requirement: Every acquisition has bound QR instructions

QR SHALL chứa ngân hàng/STK A, P đã chấp nhận, VND và memo duy nhất của attempt. Instructions SHALL bind listing/acquisition/seller/buyer/terms version; frontend không được tự chọn recipient hoặc amount khác.

#### Scenario: Buyer opens a payment instruction
- **WHEN** B có phiên mua đã được cấp giữ chỗ
- **THEN** QR thể hiện đúng P vào tài khoản A, không tài khoản cơ sở hoặc khoản phí thêm

#### Scenario: Seller attempts a bank change after the buyer locked terms
- **WHEN** A gửi yêu cầu đổi ngân hàng, thêm STK thay thế hoặc chọn revision khác trong profile
- **THEN** server từ chối BANK_PROFILE_LOCKED; instructions vẫn dùng revision đã chấp nhận, kể cả khi gọi API trực tiếp

### Requirement: QR generation does not certify money or grant usage

Protocol SHALL không bắt A kết nối SePay webhook/OAuth. Tạo QR, redirect, ảnh, mã giao dịch hoặc B khai chuyển SHALL không là BANK_VERIFIED hoặc tự chuyển holder. UI SHALL ghi rõ cần A xác nhận hoặc Owner phân xử rồi bàn giao commit.

#### Scenario: A fake success return is opened
- **WHEN** B tự mở URL thành công hoặc nộp ảnh mà A chưa xác nhận
- **THEN** không được quyền sử dụng và không có chứng từ ngân hàng do hệ thống tạo ra

### Requirement: QR instructions have scoped private access

Instructions và ảnh SHALL chỉ dành cho các bên được phép của attempt, không nằm trong public listing/report. Không trả credential hoặc raw provider URL chứa STK để browser/analytics gửi ra ngoài. QR lỗi SHALL giữ cùng attempt/deadline, không tạo lại hold.

#### Scenario: Another buyer or another facility owner requests the image
- **WHEN** tài khoản ngoài participant/case scope dùng QR/evidence id của B
- **THEN** bị từ chối và không nhận STK, ảnh hay thông tin bí mật

#### Scenario: SePay is temporarily unavailable
- **WHEN** tải ảnh QR timeout
- **THEN** hiển thị lỗi thử lại trong cùng phiên; không báo đã nhận tiền hoặc kéo dài5 phút

### Requirement: Expiry does not pretend to revoke a bank QR

Sau payBy, phiên chưa khai hợp lệ SHALL không nhận khai đúng hạn hay chiếm quyền trở lại. UI SHALL cảnh báo không chuyển theo QR cũ; báo tiền chuyển muộn SHALL đi vào xử lý tiền riêng, không khóa buyer mới hoặc lật bàn giao đã commit.

#### Scenario: Buyer pays after the listing was released
- **WHEN** B dùng ảnh QR đã lưu sau5 phút và listing đã có người khác mua
- **THEN** B được báo cáo tiền/chứng cứ với Owner nhưng không được giữ/chuyển quyền hoặc đóng băng phiên mới từ khai muộn

### Requirement: Open responsibilities lock changes to personal bank information

Hold/booking còn hiệu lực, listing/phiên mua, tranh chấp chưa chốt quyền sân hoặc khoản trả/hoàn đã xác nhận chưa xong SHALL chặn đổi thông tin/tài khoản ngân hàng cá nhân. Server SHALL xét quyền/nghĩa vụ thực tế, không theo Staff created_by. Quyết định cuối Booking SHALL gỡ riêng nguồn tranh chấp, không đóng case tiền hoặc bỏ nguồn khác. Lịch sử đã đóng và kênh nhận tiền cơ sở SHALL không tạo khóa này.

#### Scenario: A customer has a future confirmed booking
- **WHEN** user đã có tài khoản ngân hàng gửi POST tạo một revision mới dù chưa đăng chuyển nhượng
- **THEN** nhận409 BANK_PROFILE_LOCKED, tài khoản đang dùng và các snapshot không đổi

#### Scenario: Replacement uses a different profile operation
- **WHEN** user bị khóa gửi yêu cầu sửa BIN/STK/tên, thêm tài khoản thay thế, xóa hoặc chọn revision khác
- **THEN** server từ chối cùng chính sách; không có đường thay recipient qua thao tác khác

#### Scenario: Staff created several bookings for customers
- **WHEN** Staff chỉ là người tạo hộ và không có booking/nghĩa vụ cá nhân đang mở
- **THEN** các booking đó không tự khóa thông tin ngân hàng cá nhân của Staff

#### Scenario: Final usage decision releases the dispute source
- **WHEN** Booking ghi quyết định cuối quyền sân nhưng case tiền A/B vẫn OPEN và chưa có nghĩa vụ trả được bên trả xác nhận
- **THEN** case đó không còn khóa ngân hàng; server kiểm riêng booking còn lịch và các khoản trả/hoàn đã xác nhận chưa xong, không xóa case hoặc bằng chứng

#### Scenario: An acknowledged return remains unfinished
- **WHEN** quyền sân đã được quyết định nhưng A đã khai nhận trách nhiệm trả lại B và B chưa xác nhận hoàn tất
- **THEN** nguồn nghĩa vụ trả này vẫn khóa các bên liên quan, độc lập với nguồn tranh chấp đã được gỡ

### Requirement: First bank registration remains possible without enabling replacement

User chưa từng có hồ sơ ngân hàng hợp lệ SHALL được khai lần đầu dù có booking hoặc cần nhận hoàn. Server SHALL xác định lần đầu từ lịch sử thật; xóa active pointer, thay key hoặc dùng giao diện khác không reset điều kiện. Replay đúng command đã thành công SHALL trả cùng kết quả và không tạo revision thay thế.

#### Scenario: A booked customer has not registered a bank account
- **WHEN** A khai tài khoản đầu tiên để đăng nhượng hoặc B khai lần đầu để nhận hoàn hợp lệ
- **THEN** được lưu và kích hoạt một tài khoản; lần đổi/thêm tiếp theo bị khóa khi trách nhiệm còn mở

#### Scenario: The user clears the selected account and claims first registration again
- **WHEN** đã có lịch sử ngân hàng hợp lệ nhưng user gửi cờ firstRegistration hoặc key mới
- **THEN** không được áp ngoại lệ hoặc thêm tài khoản thay thế khi còn khóa

### Requirement: Bank changes and new responsibilities have a single ordered outcome

Thay ngân hàng và tạo hold/booking/listing/phiên/case SHALL được quyết định nhất quán khi chạy đồng thời. Kiểm trạng thái rồi ghi riêng không đủ. Chỉ revision được kích hoạt thành công mới được chọn cho giao dịch mới; command chưa rõ kết quả SHALL tra/replay cùng key, không tự coi thành công hoặc mở khóa.

#### Scenario: A bank replacement races a new booking hold
- **WHEN** cùng user đồng thời đổi tài khoản và giữ sân
- **THEN** nếu hold có trước thì đổi ngân hàng bị từ chối; nếu đổi có trước thì booking sau ghi nhận version mới; không có đổi ngân hàng vượt qua trách nhiệm đã mở

#### Scenario: The bank change reply is lost after activation
- **WHEN** user thử lại đúng command sau timeout
- **THEN** nhận cùng quyết định/revision, không tạo một lần đổi khác hoặc bỏ qua khóa mới

### Requirement: Bank lock status is private current and explanatory

UI/API SHALL cung cấp cho chính user tài khoản đã che số, khả năng khai lần đầu/đổi và lý do/liên kết trách nhiệm đúng quyền. Mutation SHALL kiểm lại tại server; thiếu nguồn trả503 tạm thời, không coi là được phép. Khi tất cả nguồn đóng/hết hiệu lực, user SHALL được đổi trở lại; lịch sử đơn thuần không khóa vĩnh viễn.

#### Scenario: A stale page still shows an enabled save button
- **WHEN** booking đã được tạo ở tab khác hoặc nguồn kiểm tra đang lỗi
- **THEN** server vẫn từ chối mutation tương ứng, UI làm mới lý do và không đổi tài khoản

#### Scenario: All responsibilities have ended
- **WHEN** booking/hold đã đóng hoặc hết hiệu lực, không còn listing/phiên còn hiệu lực, tranh chấp chưa chốt quyền sân hoặc nghĩa vụ trả/hoàn đã xác nhận chưa xong
- **THEN** user được đổi thông tin bằng revision mới; các chứng từ/QR cũ vẫn giữ ngân hàng đã chốt
