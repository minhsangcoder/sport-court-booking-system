# Spec Delta

## Purpose

Phản ánh đúng tiền cơ sở thực thu/hoàn và quyền sử dụng sau nâng cấp, không nhập tiền B trả trực tiếp A thành doanh thu, bank receipt hoặc công nợ của cơ sở.

## ADDED Requirements

### Requirement: Peer resale consideration does not increase facility revenue

P trả A của PEER_RESALE_V1 SHALL không tăng doanh thu, bankCollected, phí chuyển nhượng hoặc seller payable của cơ sở/SportHub. Giá trị giao dịch peer có thể hiển thị riêng với payer/recipient/protocol và mức xác nhận phù hợp.

#### Scenario: A completed peer sale follows an original court payment
- **WHEN** Owner đã thu R120000đ và B trả A P100000đ
- **THEN** cơ sở vẫn ghi thu/doanh thu120000đ, không220000đ, không phí2%/x% hoặc N phải trả A

### Requirement: Booking policy refunds reduce only the correct facility amount

H đã ghi nhận trước khi bỏ quyền hoàn riêng của người mua lại, hoặc hoàn vận hành hợp lệ ngoài phạm vi này, thực hiện bởi cơ sở SHALL ghi đúng beneficiary/funding/confirmedAt; obligation chưa trả không là đã chi. Phân xử quyền sử dụng P2P SHALL không tạo Owner refund. Tiền A trả B do hai bên xác nhận SHALL không giảm doanh thu cơ sở hoặc ghi Owner đã trả.

#### Scenario: A historical recorded buyer refund is completed
- **WHEN** nghĩa vụ H80000đ đã ghi nhận trước thay đổi trên R120000đ/P100000đ được cơ sở thực hiện
- **THEN** sau hoàn cơ sở là40000đ; P100000đ ở sổ peer riêng, không dùng ví dụ127000đ/47000đ của protocol có phí cũ

#### Scenario: New buyer cancellation does not create a facility expense
- **WHEN** B yêu cầu hoàn sau bàn giao mới với R120000đ và P100000đ
- **THEN** yêu cầu bị từ chối, cơ sở vẫn ghi120000đ và không thêm H; P chỉ ở sổ peer

### Requirement: Reports distinguish declarations acknowledgments adjudications and bank evidence

Report/receipt SHALL phân biệt B tự khai, A xác nhận nhận, Owner phân xử, BANK_VERIFIED và CASH_RECEIVED của tiền sân, và MANUALLY_RECORDED của hoàn cơ sở (chỉ còn là lịch sử từ trước mục33). Ảnh hoặc peer confirmation SHALL không được trình bày thành bank proof.

#### Scenario: Owner reviews a disputed peer sale
- **WHEN** chỉ có B khai chuyển tiền và case đang mở
- **THEN** report ghi disputed/unverified declaration, không ghi đã thu vào ngân hàng Owner hoặc đã sử dụng sân

### Requirement: Scheduled and actual use retain the original interval

Giá trị booking và booked minutes SHALL theo snapshot khoảng đã chấp nhận; used minutes SHALL theo sử dụng/check-in thực tế. Freeze, default A giữ quyền hoặc bàn giao muộn SHALL không tạo CHECKED_IN tự động hoặc kéo dài endsAt.

#### Scenario: Owner resolves an attended dispute ten minutes after start
- **WHEN** B được bàn giao rồi check-in hợp lệ cho phần thời gian còn lại
- **THEN** báo cáo không ghi sử dụng10 phút trước đó hoặc cộng thêm10 phút sau giờ kết thúc

### Requirement: Filters and source coverage remain consistent

Dashboard/chart/court table/transactions SHALL dùng cùng facility/date/timezone/protocol/money mode. Peer facts, lịch sử DEMO/legacy và Owner real cash SHALL không cộng gộp thiếu nhãn. Source thiếu SHALL hiển thị PARTIAL/UNAVAILABLE thay vì complete zero.

#### Scenario: A peer source or bank source is temporarily unavailable
- **WHEN** chỉ đọc được một phần dữ liệu của kỳ báo cáo
- **THEN** giữ số liệu có chứng cứ và trạng thái thiếu coverage, không suy khoản tiền còn lại là0 đã đối soát

### Requirement: Legacy obligations and read only monitoring remain available

F/N/H/receipts của giao dịch cũ SHALL giữ terms/date basis/assurance đã chốt. Admin chỉ giám sát và không override xác nhận tiền hoặc Owner decision. Owner/Staff SHALL chỉ đọc dữ liệu đúng scope/quyền, không có KPI doanh thu peer cho SportHub.

#### Scenario: Cutover occurs before a legacy seller payable is paid
- **WHEN** cơ sở còn nợ N93000đ từ protocol cũ
- **THEN** vẫn thấy/thực hiện nghĩa vụ cũ và ngày chi thật; new peer sale không tạo thêm N hoặc xóa khoản nợ đó

### Requirement: Faster report reads preserve complete financial meaning

Tối ưu report SHALL giữ cùng kỳ/cutoff, money mode, scope, số tiền, thứ tự và coverage của cùng facts. Công nợ trước kỳ còn mở SHALL không bị bỏ để giảm dữ liệu; paginated table không làm browser nhận toàn lịch sử. Cache/đọc nhanh SHALL không tự xác nhận tiền, mở bank lock hoặc biến PARTIAL thành COMPLETE.

#### Scenario: A short report window includes an older unpaid refund
- **WHEN** Owner xem tuần hiện tại sau tối ưu query/pagination
- **THEN** vẫn có nghĩa vụ cũ cần tính, tổng/coverage đúng và chỉ trả trang giao dịch được yêu cầu

#### Scenario: A source fails while an older report result is available
- **WHEN** nguồn tiền chưa xác minh được cho lần tải hiện tại
- **THEN** hiển thị freshness/coverage phù hợp, không dùng dữ liệu cũ như chứng cứ COMPLETE hoặc giấy phép thay ngân hàng

### Requirement: Load improvements have comparable measurable acceptance

Đường tải được chọn tối ưu SHALL có baseline và kết quả production cold/warm cùng dữ liệu/điều kiện/behavior. Mục tiêu SHALL giảm ít nhất20% content-ready hoặc API p95 của nút thắt đã xác nhận; route kiểm soát không tăng quá max(10% baseline,50ms). Kết quả SHALL ghi request/bytes và giới hạn phép đo; thiếu số đo hoặc chỉ chia file không đủ kết luận hoàn tất.

#### Scenario: A benchmark uses only mocked dependencies
- **WHEN** người triển khai báo thời gian API từ fixture không có socket/provider latency
- **THEN** kết quả được ghi đúng giới hạn, có integrated trace riêng và không gọi là tốc độ ngân hàng thật/toàn hệ thống

#### Scenario: A refactor reduces file size but does not improve load time
- **WHEN** thời gian vẫn chưa đạt mục tiêu hoặc dữ liệu test đã bị cắt khác baseline
- **THEN** không đánh dấu hoàn tất tối ưu dựa trên số dòng, ghi việc còn lại và đo lại trên cùng điều kiện

### Requirement: Receipts without Payment evidence are excluded rather than guessed

Tiền sân REAL của cơ sở SHALL chỉ có hai loại thu: BANK (SePay đã xác minh) và CASH (tiền mặt tại quầy). Khi payment-service tạm thời không trả được chứng từ của một khoản thu, khoản đó SHALL không được cộng vào ALL, CASH hoặc BANK, để ALL luôn bằng CASH + BANK. Báo cáo SHALL ghi moneyAvailability=UNAVAILABLE (hoặc PARTIAL nếu còn khoản khác đã phân loại) kèm lý do REAL_PAYMENT_FACTS_UNAVAILABLE, và SHALL không hiển thị COMPLETE với số0.

#### Scenario: Payment evidence is unavailable for every receipt
- **WHEN** khoản thu sân100000đ có trong booking nhưng Payment không trả chứng từ và tín dụng ngân hàng
- **THEN** courtCollected là0, moneyAvailability=UNAVAILABLE và lý do có REAL_PAYMENT_FACTS_UNAVAILABLE; dữ liệu chuyển nhượng vẫn hiển thị riêng
