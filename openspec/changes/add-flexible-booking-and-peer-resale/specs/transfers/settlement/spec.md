# Spec Delta

## Purpose

Quy định giao dịch mới trả trực tiếp A, bàn giao sau hai bên xác nhận hoặc Owner phân xử, với hạn giữ rõ ràng và chỉ một lần chuyển nhượng thành công cho mỗi booking.

## ADDED Requirements

### Requirement: New peer acquisitions pay the seller without facility resale fees

Protocol PEER_RESALE_V1 SHALL chỉ nhận booking gốc REAL đã đủ chứng từ thu áp dụng, dùng P>0, P≤giá booking gốc và trả đúng P vào A. Không tính phí chuyển nhượng x%, phí SportHub hoặc nghĩa vụ Owner trả A/B do tranh chấp. Booking DEMO hiện có chỉ giữ lịch sử/quyền sử dụng và phiên giao dịch đã mở, không tạo thêm listing/acquisition mới theo phí cũ và không thành nguồn P2P tiền thật.

#### Scenario: Seller lists a booking for one hundred thousand
- **WHEN** B chấp nhận P100000đ và tạo phiên mua
- **THEN** QR vào A100000đ, không khoản trả A từ Owner hoặc fee settlement mới

#### Scenario: A demo booking is submitted to the real peer API
- **WHEN** user gọi trực tiếp quote/publish/acquire P2P thật cho booking gốc DEMO
- **THEN** server từ chối bằng lý do phù hợp; không tạo QR tiền thật, bank proof hoặc khoản thu/hoàn REAL từ dữ liệu DEMO

### Requirement: Removed fee based creation code is absent from production

Các hàm create/quote/edit/acquire theo mô hình phí cũ không có caller production SHALL bị xóa cùng calculator/client/config chỉ dùng cho chúng. Lịch sử, payable/cancel phiên chưa trả và workflow đối soát đã mở SHALL giữ nguyên quyền/proof/idempotency. Kiểm thử lịch sử SHALL seed fixture riêng, không gọi lại creator đã bỏ.

#### Scenario: An existing legacy acquisition awaits reconciliation
- **WHEN** bộ đối soát nhận lại chứng từ hoặc quyết định bàn giao của phiên đã mở
- **THEN** vẫn kiểm chứng từ, chống áp dụng trùng và giữ lịch sử; không cần khôi phục API tạo giao dịch phí cũ

### Requirement: A new acquisition holds for no more than five minutes

Phiên mua SHALL chỉ sẵn sàng sau được cấp claim/lease, với payBy không quá5 phút từ lúc cấp và không vượt hạn mua/giờ chơi. Chưa khai hợp lệ trước payBy SHALL release có điều kiện đúng attempt; retry/tab đóng không gia hạn.

#### Scenario: Buyer only chats until the deadline
- **WHEN** hết payBy mà B chưa có ảnh READY và lệnh khai đã commit
- **THEN** giải phóng claim, mở lại listing nếu còn hợp lệ và giữ conversation

#### Scenario: Upload races expiry
- **WHEN** ảnh mới bắt đầu upload trước hạn nhưng khai thanh toán commit sau hạn
- **THEN** không được coi là khai đúng hạn hoặc khóa buyer mới; B có thể báo tiền muộn riêng

### Requirement: Valid payment declaration waits for the seller

B khai đúng hạn với ảnh/reference SHALL chuyển phiên sang PAYMENT_DECLARED, giữ freeze và thông báo cho A. Hết5 phút sau khai SHALL không tự mở lại listing; B không được tự đánh dấu nhận sân.

#### Scenario: Seller is offline after the buyer declares payment
- **WHEN** B đã nộp đủ chứng cứ đúng hạn nhưng A chưa phản hồi
- **THEN** phiên vẫn chờ A, B có quyền mở khiếu nại và chưa có credential sử dụng

### Requirement: Seller receipt confirmation initiates authoritative handoff

Chỉ A hiện hành của attempt SHALL xác nhận đã nhận đủ P. Ack đúng phase/version/terms SHALL khởi động bàn giao; UI chỉ báo B đã nhận quyền sau quyết định Booking commit. Ack không được ghi thành BANK_VERIFIED.

#### Scenario: Seller acknowledgment response is lost
- **WHEN** A gửi lại cùng key sau timeout
- **THEN** phục hồi cùng ack/decision, không tạo thêm handoff hoặc yêu cầu B trả lần nữa

#### Scenario: A buyer tries to impersonate the seller
- **WHEN** B hoặc actor khác gọi confirm-receipt với sellerId trong body
- **THEN** bị từ chối và không đổi holder

### Requirement: Denied receipt and buyer complaints create disputes

A báo chưa nhận sau khai hoặc B khiếu nại A không xác nhận SHALL tạo case đúng attempt và giữ freeze. Chủ thể, P, bank revision và ảnh đã nộp SHALL không thay đổi âm thầm khi case mở.

#### Scenario: Seller denies receiving the declared payment
- **WHEN** A chọn chưa nhận sau B nộp ảnh và mã
- **THEN** chuyển DISPUTED, cho hai bên bổ sung chứng cứ và Owner xử lý; không tự kết luận B gian dối

### Requirement: A booking can be successfully resold only once

Mỗi booking SHALL chỉ có một completed resale trong policy mới. Booking đã có handoff thành công, kể cả Owner quyết định cho B hoặc lịch sử legacy đã resale, SHALL không tạo listing/quote/acquisition mới. Failed/expired attempts không tiêu quyền này.

#### Scenario: The buyer tries to resell
- **WHEN** A→B đã completed và B gọi API đăng lại
- **THEN** bị từ chối dù gửi API trực tiếp, đổi listingId hoặc dùng giao diện cũ

#### Scenario: Concurrent attempts try to consume the last resale right
- **WHEN** hai lệnh complete/case decision cạnh tranh
- **THEN** chỉ một quyết định có thể chuyển holder và tiêu quyền; replay trả cùng kết quả

### Requirement: Accepted price and bank terms cannot mutate during an acquisition

Seller/buyer SHALL chấp nhận cùng version/hash của booking/interval/P/bank recipient/policy. Đổi ngân hàng khi còn trách nhiệm SHALL bị từ chối; chat không đổi terms đã khóa. Request dùng hash cũ hoặc bank revision chưa được kích hoạt SHALL bị từ chối.

#### Scenario: Seller changes the listing while a buyer is paying
- **WHEN** A cố đổi P, STK hoặc khung giờ của phiên đang khóa/đã khai
- **THEN** phiên giữ nguyên terms và mutation không được áp dụng

### Requirement: Cutover preserves previously accepted protocols

Giao dịch legacy đã khóa/thu tiền và các khoản Owner đang nợ SHALL tiếp tục đúng protocol/terms cũ. Chỉ new peer listing dùng luồng mới; SHALL không biến payment cũ thành lời khai hoặc bắt buyer trả thêm.

#### Scenario: Old Owner collection is still awaiting handoff
- **WHEN** triển khai peer flow trong lúc một acquisition payOS cũ đã nhận tiền
- **THEN** vẫn đối soát/bàn giao, hoàn thu lỗi và xử lý nghĩa vụ đã ghi nhận theo protocol cũ; không tạo quyền hoàn mới khi người mua lại tự hủy, không chuyển recipient sang A hoặc xóa F/N đã chốt

### Requirement: Open resale responsibilities prevent bank replacement

Listing còn mở SHALL khóa thay ngân hàng của A; phiên mua đang hoạt động/chờ A/tranh chấp SHALL khóa các bên liên quan. Trách nhiệm SHALL được ghi nhận trước publish/READY. Rút listing hoặc hết phiên SHALL chỉ gỡ nguồn đó; không mở khóa khi còn booking, case hoặc tiền chưa xong. Bank revision đã chốt SHALL không thay theo profile mới.

#### Scenario: Seller withdraws a listing but keeps the upcoming booking
- **WHEN** A rút listing rồi gọi đổi STK trong khi vẫn giữ booking chưa kết thúc
- **THEN** vẫn bị BANK_PROFILE_LOCKED theo booking, không được lách bằng rút rồi đăng lại

#### Scenario: A failed acquisition leaves only chat history
- **WHEN** phiên đã hết, không có khai tiền/case/nghĩa vụ khác và B không có booking khác
- **THEN** conversation còn nhắn được nhưng không giữ khóa ngân hàng B

#### Scenario: A completed handoff changes the responsibility holder
- **WHEN** B nhận booking, A không còn case/tiền/listing khác phải xử lý
- **THEN** B tiếp tục bị khóa theo booking hiện hành, A được đánh giá lại theo trách nhiệm còn lại của chính mình
