# Spec Delta

## Purpose

Đưa lời mời và thao tác chốt chia tiền vào ngay phần thanh toán nhóm.

## ADDED Requirements

### Requirement: Groups may be created fifteen minutes before play
Nhóm mới SHALL được tạo khi còn ít nhất15 phút trước giờ chơi. Hạn thanh toán SHALL là min(thời điểm tạo +30 phút, giờ chơi -5 phút), cập nhật cùng hạn giữ chỗ và admission claim. Nhóm cũ giữ hạn đã cấp.

#### Scenario: Last-minute group collects full payment
- **WHEN** tạo nhóm lúc14:15 cho14:30–15:30
- **THEN** nhóm được tạo, hạn góp tiền14:25; chỉ xác nhận khi có đủ chứng từ tiền sân hợp lệ

#### Scenario: Too little lead time remains
- **WHEN** yêu cầu tạo nhóm chỉ còn14 phút59 giây trước giờ chơi
- **THEN** từ chối với thông báo15 phút, không tạo booking hoặc tiêu thụ hold

### Requirement: Organizer can pay outstanding member contributions
Người tổ chức SHALL thấy tổng còn thiếu và được thanh toán từng phần chưa đóng. Nếu thành viên đã mở QR SePay nhưng chưa có bank credit, server SHALL cho người tổ chức thay bằng đơn trả thay cùng nghĩa vụ. Không cho thành viên chiếm lại đơn của người tổ chức, không thay đơn đã có tiền một phần; không sửa payer/snapshot chứng từ đã có.

#### Scenario: Organizer pays for a member who has opened a QR
- **WHEN** thành viên chưa chuyển tiền và người tổ chức chọn thanh toán thay
- **THEN** QR cũ ngừng là đơn chờ, đơn mới ghi payer là người tổ chức; thanh toán đủ mọi phần mới xác nhận nhóm

#### Scenario: Both payer orders receive bank transfers
- **WHEN** webhook hợp lệ đến cho cả QR thành viên và QR trả thay
- **THEN** chỉ một khoản áp dụng vào nghĩa vụ, phần trùng được lưu để đối soát, không cộng gấp đôi hoặc bỏ mất chứng từ

### Requirement: Group checkout displays an actionable invitation

Trang nhóm SHALL hiển thị mã mời, nút sao chép và liên kết xem QR tại phần thanh toán cho người tổ chức khi nhóm còn nhận thành viên. Nhóm đã khóa danh sách, đủ người hoặc kết thúc SHALL không phát hành lời mời mới. Thành viên khác SHALL nhập mã qua luồng xác minh/tham gia hiện có.

#### Scenario: Another customer joins using the checkout invitation
- **WHEN** người khác nhập mã hợp lệ và xác nhận tham gia
- **THEN** người đó mở trang nhóm; trang của người tổ chức tự tải lại dữ liệu thành viên/tiến độ qua polling mỗi 5 giây mà không mất thao tác đang nhập

### Requirement: Organizer explicitly finalizes the payment split

Thành viên tham gia SHALL không tự chốt danh sách hoặc tạo giao dịch. Người tổ chức SHALL bấm “Chia đều và bắt đầu thanh toán” hoặc chọn chia tùy chỉnh. Server SHALL tính phần tiền từ danh sách hiện tại dưới group lock; bấm lặp không tạo thanh toán trùng. Khi đã bắt đầu thanh toán SHALL không thay đổi thành viên/phân bổ.

#### Scenario: Two members split a court price of 120000 VND
- **WHEN** người tổ chức chốt chia đều sau khi người thứ hai đã tham gia
- **THEN** mỗi người thấy phần60000đ và nút thanh toán của mình; mã mời được ẩn và không nhận thêm người

### Requirement: Group checkout excludes the removed resale refund panel

Trang nhóm SHALL không render “Hủy theo chính sách cơ sở (Người mua chuyển nhượng)” hoặc nút xem khoản hoàn cho người mua lại. Các nghĩa vụ lịch sử vẫn hiển thị trong phần thu chi của người dùng.

#### Scenario: An existing confirmed group is viewed
- **WHEN** người tổ chức mở nhóm đã thanh toán đủ
- **THEN** không thấy panel hoàn cho người mua lại và không thể mở lại mời/chia tiền
