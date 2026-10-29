# Spec Delta

## Purpose

Không tạo lệnh hủy theo chính sách hoàn của cơ sở cho bất kỳ vai trò nào; bảo toàn nghĩa vụ đã ghi nhận và hoàn thu lỗi. Quyết định mục 24 thay các mô tả cũ cho phép Owner/Staff hủy theo chính sách.

## ADDED Requirements

### Requirement: Removed facility policy cancellation cannot be invoked by operators

Controller/service hủy theo chính sách SHALL bị xóa; endpoint cũ trả404, không ghi dữ liệu cho bất kỳ vai trò, loại booking hoặc chế độ tiền. UI SHALL không có form hủy này. Hủy nháp chưa thanh toán của chính người đặt/người tạo tại quầy và hoàn thu lỗi SHALL giữ guard hiện hành. BOOKING_REFUND chỉ xử lý nghĩa vụ đã ghi nhận, không cấp lại quyền hủy.

#### Scenario: Owner requests cancellation with a historical policy quote
- **WHEN** Owner hoặc Staff được cấp BOOKING_REFUND gửi cancellation-quote/cancel-with-policy, kể cả quote cũ chưa dùng
- **THEN** trả404, không hủy quyền dùng sân, giải phóng reservation hoặc tạo khoản hoàn; nghĩa vụ cũ không bị xóa

### Requirement: Resold bookings do not grant new cancellation refund benefits

Booking đã bàn giao qua chuyển nhượng SHALL không tạo báo giá hoặc nghĩa vụ hoàn mới khi tự hủy, cả REAL/P2P và DEMO. Theo mục26, endpoint đã xóa SHALL trả404 mà không đọc handoff hoặc lấy court lock. Giá mua lại P và tỷ lệ policy cũ SHALL không tạo quyền hoàn.

#### Scenario: Buyer or operator requests a refund after handoff
- **WHEN** B, Owner hoặc Staff gửi quote/cancel-with-policy cho booking đã bàn giao A→B, kể cả dùng quote cũ
- **THEN** server từ chối, không đổi holder/trạng thái booking và không tạo nghĩa vụ hoàn

#### Scenario: Cancellation requests race after handoff
- **WHEN** nhiều request hoàn mới đến đồng thời sau bàn giao
- **THEN** tất cả bị từ chối và không ghi refund/receipt giả

### Requirement: Customer screens do not advertise resale cancellation refunds

Customer booking/payment SHALL bỏ khối phụ gồm mô tả 100% chuyển nhượng, chi tiết hoàn riêng cho người mua lại và ghi chú check-in/đối soát phía dưới. Chi tiết booking và các trang chuyển nhượng/cơ sở SHALL không hiển thị form hoặc quảng bá tỷ lệ hoàn riêng cho người mua lại. Luồng chuyển nhượng và kiểm tra đủ tiền check-in SHALL vẫn hoạt động.

#### Scenario: Customer opens the payment summary
- **WHEN** snapshot cũ chứa rateBps=9500
- **THEN** vẫn hiển thị quy định không hoàn khi tự hủy, không có chi tiết hoàn95% hoặc khối phụ đã bỏ

### Requirement: Recorded obligations and unrelated refunds remain intact as history

Khoản hoàn đã ghi nhận, funding, beneficiary, snapshots, F/N/H, nghĩa vụ chi/hoàn, biên nhận MANUALLY_RECORDED và lịch sử thu chi SHALL được giữ làm dữ liệu lịch sử cho báo cáo và màn giám sát Admin chỉ đọc. Lịch sử group/counter và hoàn thu lỗi SHALL không bị xóa; điều này SHALL không cho phép tạo hủy theo chính sách mới.

#### Scenario: A historical refund receipt is reported
- **WHEN** báo cáo kỳ có nghĩa vụ hoàn đã được ghi MANUALLY_RECORDED trước mục33
- **THEN** báo cáo và Admin vẫn đọc đúng người nhận, số tiền và thời điểm ghi nhận; không có thao tác sửa hoặc thực hiện lại

### Requirement: Facility manual payout and refund execution is removed

Theo mục33, cơ sở SHALL không còn màn hình hoặc API thực hiện chi trả/hoàn thủ công: khối “Trả người chuyển nhượng và hoàn tiền” (REAL và mô phỏng) bị gỡ khỏi trang vận hành; `/bookings/facility-finance/*` không còn tồn tại; bảng operations, commands, audit của luồng này bị xóa (migration V21) trong khi nghĩa vụ và biên nhận lịch sử được giữ. Cách xử lý nghĩa vụ chưa thực hiện và nguồn khóa ngân hàng tương ứng là điểm mở của mục33, chưa được quyết định.

#### Scenario: Owner opens booking operations
- **WHEN** Owner hoặc Staff có BOOKING_REFUND/TRANSFER_PAYOUT mở Vận hành lịch đặt
- **THEN** không thấy khối chi trả/hoàn, không có nút nhận xử lý/mở QR/xác nhận đã chuyển, và gọi `/bookings/facility-finance/*` trả404

### Requirement: Customer pages omit facility payout and refund dashboards

Customer booking, group, transfer-list and acquisition pages SHALL không hiển thị khối “Khoản cơ sở trả và hoàn của bạn” hoặc bản mô phỏng tương ứng. Component, dynamic wrapper và reads chỉ phục vụ khối này SHALL được gỡ; dữ liệu và thao tác vận hành của cơ sở SHALL giữ nguyên.

#### Scenario: Customer opens a booking or transfer page
- **WHEN** Customer mở trang booking, nhóm, danh sách chuyển nhượng hoặc chi tiết giao dịch cũ, cả REAL và DEMO
- **THEN** không thấy khối trả/hoàn của cơ sở và trang không gọi API mine chỉ để dựng khối đó; thông tin booking, thanh toán và chuyển nhượng còn lại vẫn hiển thị

### Requirement: Peer disputes resolve usage only

Owner SHALL chỉ phân xử quyền sử dụng; tranh chấp A–B không tạo hoàn P hoặc H từ cơ sở. Tiền A–B do hai bên khai/xác nhận riêng.

#### Scenario: Owner awards usage to A
- **WHEN** quyết định tranh chấp giữ quyền sân cho A
- **THEN** không phát sinh khoản cơ sở hoàn B hoặc tự xác nhận B đã được trả tiền
