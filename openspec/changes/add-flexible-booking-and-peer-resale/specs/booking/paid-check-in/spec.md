# Spec Delta

## Purpose

Giữ điều kiện đủ tiền sân và CHECKED_IN hiện tại, đồng thời bảo đảm freeze, quyết định chuyển quyền một lần và credential của chủ cũ/mới nhất quán trong giao dịch peer.

## ADDED Requirements

### Requirement: Only the current Customer holder can read booking checkin credentials

Mã10 ký tự và signed QR/token SHALL chỉ đọc được bởi Customer đang sở hữu booking khi quyền dùng không freeze. Owner/Staff SHALL nhập mã/quét QR khách xuất trình; API danh sách, tìm kiếm cơ sở, chi tiết vận hành và mutation/replay của người vận hành SHALL không trả credential của khách. Khóa ký QR SHALL luôn chỉ ở Backend. Vai trò vận hành thêm vào tài khoản SHALL không mở quyền đọc mã của người khác.

#### Scenario: Owner views a paid Customer booking
- **WHEN** Owner/Staff đọc booking qua danh sách, chi tiết hoặc operations
- **THEN** không nhận được bookingCode, token hoặc ảnh QR của khách; Customer hiện tại vẫn xem mã10 ký tự và QR từ trang cá nhân

### Requirement: Customer checkin UI omits the raw signed token

Trang booking SHALL hiển thị ảnh QR và mã thủ công10 ký tự cho current Customer holder, không hiển thị chuỗi token dài trong text/details. Token, chữ ký, hạn sử dụng và version SHALL vẫn phục vụ scanner/check-in; thao tác làm mới QR giữ nguyên.

#### Scenario: Customer expands the booking checkin area
- **WHEN** Customer có quyền đọc credential mở chi tiết booking
- **THEN** thấy QR và mã10 ký tự, không có khối “Mã dùng khi nhập thủ công” chứa token dài; mã của người khác vẫn không được cấp

### Requirement: Counter guest attendance does not expose a booking code

Guest OFFLINE_COUNTER holder=NULL SHALL được check-in qua lệnh xác nhận có mặt riêng mà không xuất credential. Lệnh SHALL kiểm quyền BOOKING_CHECK_IN đúng cơ sở, đủ tiền, trạng thái CONFIRMED, transfer freeze và cửa sổ thời gian chung; SHALL từ chối booking online hoặc đã liên kết Customer. Staff SHALL không trở thành owner.

#### Scenario: Operator attempts to bypass an account holders credential
- **WHEN** Owner gọi check-in-guest cho booking có Customer hoặc booking online
- **THEN** từ chối và không đổi trạng thái; Guest hợp lệ chỉ chuyển CHECKED_IN sau đủ tiền và đúng giờ

### Requirement: Original court payment remains required for use and resale

Booking SHALL có tiền sân gốc đủ và đã áp dụng hợp lệ trước check-in hoặc đăng/bàn giao chuyển nhượng. P2P thật SHALL chỉ nhận booking REAL có chứng từ thu thật; DEMO hiện có giữ quyền sử dụng theo luồng cũ. Peer image, seller ack hay Owner decision SHALL không bù tiền sân còn thiếu hoặc làm native bank proof giả.

#### Scenario: Seller tries to transfer an unpaid booking
- **WHEN** booking chỉ có hold/chưa đủ tiền sân dù A/B cùng xác nhận đã trả P
- **THEN** không đăng hoặc bàn giao được và không cấp check-in

### Requirement: Usage remains frozen while an acquisition or dispute is unresolved

Khi active peer lease/payment declaration/dispute đang freeze, mọi đường cấp credential và QR/mã/HTTP check-in SHALL áp cùng guard cho A/B. Giữ holder A và status booking hiện có cho tới quyết định có hiệu lực, không tạo IN_PROGRESS.

#### Scenario: A uses an old booking code during the dispute
- **WHEN** A đưa QR/mã hoặc gọi check-in API trong lúc lease bị freeze
- **THEN** bị từ chối dù tiền sân gốc đã đủ; B cũng chưa có quyền chỉ nhờ ảnh chuyển khoản

### Requirement: Peer handoff commits ownership credential and one time rights atomically

Handoff SHALL chốt đúng A/B/booking/interval/terms, nguồn xác nhận hợp lệ và quyền resale chưa dùng. Holder, slot holder, token/code version, decision, one-time right và release claim SHALL cùng hiệu lực; creator/giá/receipt sân gốc không đổi.

#### Scenario: Handoff response is lost after commit
- **WHEN** Transfer hoặc client hỏi lại cùng command
- **THEN** nhận cùng quyết định/holder/token version; không tạo lần resale thứ2, bank payment giả hoặc đổi giá sân

### Requirement: The new buyer receives usage only after the authoritative decision

B SHALL chỉ được dùng sân khi Booking commit COMPLETED cho attempt và thời gian sử dụng còn hợp lệ. Acknowledgment đang đồng bộ hoặc projection Transfer báo trước SHALL không đủ để cấp quyền; A mất mã/token cũ sau commit.

#### Scenario: Seller has confirmed but the handoff is still processing
- **WHEN** A đã nhận đủ tiền, RPC/event chưa có quyết định Booking
- **THEN** UI báo đang bàn giao, B chưa có credential và không phải thanh toán lần nữa

### Requirement: Owner can award remaining time only for an attended unresolved case

Handoff thông thường SHALL trước startsAt. Ngoại lệ Owner sau startsAt SHALL cần có bên được ghi có mặt đúng giờ, booking còn CONFIRMED/chưa check-in, chưa có quyết định cuối và now<endsAt. Chỉ phần lịch còn lại được sử dụng, không tự bù giờ.

#### Scenario: An attended buyer is awarded the remaining booking
- **WHEN** B có mặt đúng giờ, Owner quyết định10 phút sau startsAt và còn50 phút trước endsAt
- **THEN** có thể bàn giao B và check-in phần còn lại, endsAt/giá snapshot không đổi

#### Scenario: Owner tries to revive an expired or already finalized use right
- **WHEN** booking đã CHECKED_IN, hết endsAt hoặc đã có quyết định cuối A giữ quyền do cả hai vắng
- **THEN** không bàn giao lại quyền dùng quá khứ; việc xử lý tiền vẫn độc lập

### Requirement: Terminal decisions are mutually exclusive under concurrency

A ack, Owner award, expiry/default A retains, cancellation và check-in SHALL không đồng thời tạo kết quả mâu thuẫn. Quyền/claim cuối phải đọc và phục hồi được sau crash; không vừa giữ A vừa cấp B hoặc hoàn booking do một timeout chưa rõ.

#### Scenario: Owner award races a timeout and check in
- **WHEN** các lệnh cạnh tranh trên cùng booking
- **THEN** chỉ một kết quả thỏa guard được commit; holder, credential, status, freeze và quota nhất quán

### Requirement: Existing booking lifecycle and special ownership are preserved

CHECKED_IN→COMPLETED và cửa sổ check-in SHALL giữ nghiệp vụ hiện hữu. Guest holder NULL không thành Staff owner; nhóm giữ điều kiện từng contribution/participant và không tự được mở bán một phần hoặc resale nhóm bởi change này.

#### Scenario: A counter guest is checked in after paying the facility
- **WHEN** Guest đã đủ tiền và không có peer lease hợp lệ
- **THEN** Staff đúng quyền vận hành check-in như hiện tại; Guest không cần Customer giả và Staff không thành chủ booking
