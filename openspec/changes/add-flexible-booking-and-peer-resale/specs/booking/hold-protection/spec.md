# Spec Delta

## Purpose

Bảo vệ quyền tiếp cận giờ sân bằng hạn mức giữ chỗ chung, thời gian nghỉ có giải thích và chế tài dựa trên quyết định gian dối, không làm mất quyền của booking đã thanh toán.

## ADDED Requirements

### Requirement: Facility ownership separates personal booking from counter operations

The server SHALL reject personal holds/new bookings for the facility owner, using ownership from Facility service and failing closed when it cannot be verified. This SHALL cover hold replay, group creation and linked counter Customers. Owner counter booking for other Customers/guests SHALL remain supported without making the operator the holder.

#### Scenario: An Owner uses a personal or old hold to book their own court
- **WHEN** the facility owner requests an online hold, replays a hold or converts an old hold into an individual/group booking
- **THEN** the server rejects the new action without creating a booking or new hold claim; existing bookings and payments are not rewritten

#### Scenario: Owner operates the counter for a customer
- **WHEN** Owner chooses their facility in the counter workspace and supplies a guest or another Customer's details
- **THEN** the booking creator is Owner, holder is the linked Customer orNULL, and normal sufficient-payment/check-in guards apply

#### Scenario: Owner cannot disguise self-booking as a linked counter customer
- **WHEN** the guest phone resolves to the facility owner's Customer account
- **THEN** new booking creation is rejected, including direct API requests

#### Scenario: Owner finds operational actions in the interface
- **WHEN** Owner opens the account menu or facility sidebar
- **THEN** explicit links open scoped booking/check-in and counter booking workspaces; Owner does not need a Staff role to operate their own facility

#### Scenario: Owner books another facility personally
- **WHEN** an Owner with Customer access books a facility belonging to another person
- **THEN** normal personal booking remains allowed, subject to existing availability, admission and payment rules

### Requirement: One active online claim is shared across booking and resale

Mỗi user SHALL có tối đa1 claim đang giữ trên toàn hệ thống cho booking online và mua chuyển nhượng, kể cả phiên chờ A/tranh chấp. Quote hoặc chat không tạo claim. Retry cùng yêu cầu SHALL dùng claim/hạn cũ, không gia hạn hoặc chiếm thêm.

#### Scenario: Requests race on different courts and services
- **WHEN** cùng user đồng thời giữ booking và mua listing ở sân khác
- **THEN** tối đa một claim được cấp; request còn lại có lý do và liên kết lượt đang giữ

#### Scenario: Group and operational counter bookings
- **WHEN** hold chuyển thành nhóm hoặc Staff đặt quầy bằng grant hợp lệ
- **THEN** nhóm giữ claim organizer hiện có; quầy dùng phạm vi operator riêng, không tạo Customer giả hoặc áp một claim cho toàn bộ khách của Staff

### Requirement: Repeated abandonment triggers progressive restrictions

Lần bỏ/hết hạn thứ3 liên tiếp chưa thành công trong60 phút SHALL chặn tạo claim online30 phút; lần5 trong ngày Việt Nam SHALL chặn24 giờ. Thành công reset chuỗi liên tiếp, không xóa tổng trong ngày hoặc block đã tạo. Các hạn SHALL hiển thị retryAt theo thời gian server.

#### Scenario: Third and fifth abandonment
- **WHEN** user bỏ hold lần3 trong giờ rồi đạt lần5 trong ngày
- **THEN** áp lần lượt30 phút và24 giờ, không mở lại chỉ vì đã sang ngày mới

### Requirement: Abandonment counting is durable and exactly once

Release raw hold, cancel booking pending, expiry và acquisition chưa khai đúng hạn SHALL được tính một lần cho một lượt giữ. Replay, chuyển raw hold thành booking, lỗi hệ thống/provider, conflict/price change hoặc Owner hủy vận hành SHALL không tăng lỗi của user.

#### Scenario: Duplicate expiry follows an explicit release
- **WHEN** worker nhận lặp sự kiện cho lượt user đã hủy hoặc timeout sau khi gửi mutation thành công
- **THEN** chỉ một abandonment được tính, cùng effectiveAt và lý do

#### Scenario: Counter cache is unavailable
- **WHEN** counter store lỗi hoặc chưa phục hồi xong
- **THEN** tạo claim mới thất bại tạm thời có thông báo; đối soát tiền/case/booking đã mở vẫn được phục vụ và counters phục hồi từ lịch sử

### Requirement: The abandoning user cannot immediately regrab overlapping time

Trong15 phút sau bỏ/hết hạn, chính user SHALL không giữ lại khoảng giao nhau trên cùng court, kể cả dịch đầu/cuối giờ để lách. Người khác SHALL vẫn được đặt khoảng đã được release; khoảng liền kề không giao nhau không bị cấm bởi rule này.

#### Scenario: User shifts the abandoned interval
- **WHEN** user vừa hết hold18–19 rồi xin17:30–18:30 trên cùng sân
- **THEN** bị chặn tới retryAt; user khác được giữ18–19 nếu còn hợp lệ

### Requirement: Slot holding requires a verified active account and a user rate budget

Tạo hold/mua lại SHALL yêu cầu account ACTIVE và email hoặc điện thoại đã xác thực từ nguồn server. Hai loại lệnh giữ SHALL chia sẻ tối đa5 request trong60 giây/userId, có429/Retry-After; IP limiter không thay thế quota user.

#### Scenario: A signed stale token or forged headers are used
- **WHEN** user chưa verified, bị inactive hoặc tự gửi header verified/role dù JWT ký đúng
- **THEN** không được tạo claim; gọi API trực tiếp không vượt guard

#### Scenario: Sixth request follows rapid cancellations
- **WHEN** user gửi request giữ thứ6 trong60 giây, dù các lượt trước đã hủy
- **THEN** nhận429 cùng hạn thử lại; không vượt giới hạn nhờ đổi court, route hoặc IP

### Requirement: Only adjudicated intentional false payment claims create strikes

Một kết luận Owner hợp lệ xác định B cố ý khai chuyển tiền giả SHALL tạo1 strike. Mỗi vụ cấm chợ30 ngày; đủ3 strike cấm chợ vĩnh viễn. Thiếu bằng chứng, A phủ nhận, không đến sân hoặc replay quyết định SHALL không tự thêm strike.

#### Scenario: An intentional fraud decision is retried
- **WHEN** Owner xác định gian dối rồi gửi lại quyết định do mất phản hồi
- **THEN** chỉ có một strike và một hạn cấm tương ứng, không tăng thành hai vụ

#### Scenario: Market restricted user owns a paid booking
- **WHEN** user bị cấm chợ nhưng có booking đã trả hoặc case tiền đang mở
- **THEN** vẫn xem/sử dụng booking hợp lệ, bổ sung chứng cứ/nhận hoàn; không tạo listing hoặc mua mới

### Requirement: Counter access is not a client controlled escape hatch

Chế độ quầy SHALL đòi Owner/Staff và grant hiện hành đúng cơ sở cùng audit. Duration, giá và overlap vẫn áp dụng. User thường SHALL không vượt admission bằng cách đổi query/body sang counter.

#### Scenario: A customer adds counter true
- **WHEN** user không có grant quầy gọi giữ chỗ với counter=true
- **THEN** bị từ chối, không có claim/reservation và không bị gán thành Staff owner

### Requirement: Booking responsibilities participate in the personal bank change guard

Hold còn hạn và booking chưa kết thúc SHALL khóa đổi ngân hàng của current holder/người tham gia nhóm có quyền hoặc nghĩa vụ tiền, kể cả booking quầy liên kết Customer. Admission và thay ngân hàng SHALL có thứ tự quyết định chung; không chỉ dựa vào kiểm tra từ frontend. Booking mới SHALL không bắt buộc khai ngân hàng nếu nghiệp vụ chưa cần tài khoản nhận tiền.

#### Scenario: A bank update and a counter booking for a linked customer race
- **WHEN** Staff tạo booking cho Customer trong lúc Customer đổi ngân hàng
- **THEN** trách nhiệm gắn Customer và được sắp thứ tự với lần đổi; không áp khóa theo Staff created_by

#### Scenario: A first booking has no bank account
- **WHEN** user đủ điều kiện giữ sân nhưng chưa khai ngân hàng
- **THEN** vẫn giữ được sân theo quota; được khai ngân hàng lần đầu sau đó, không được dùng ngoại lệ để thay tài khoản đã có

#### Scenario: An expired hold is waiting for a delayed worker
- **WHEN** hold đã hết hạn server và không còn booking/case/nghĩa vụ khác
- **THEN** không giữ khóa ngân hàng chỉ vì projection chưa chạy; lịch sử/abandonment vẫn được ghi đúng một lần
