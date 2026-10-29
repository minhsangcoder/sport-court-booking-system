# Spec Delta

## Purpose

Cho khách đặt một khoảng giờ liên tục theo bước30 phút và trả đúng giá của từng phần thời gian, đồng thời bảo toàn lịch và giá đã chốt trước nâng cấp.

## ADDED Requirements

### Requirement: Range previews are bounded and never create implicit holds

Availability/quote SHALL giới hạn dữ liệu theo sân/ngày/khoảng đang xem thay vì chờ toàn bộ lịch sử. Đổi lựa chọn liên tục SHALL chỉ trình bày kết quả mới nhất, không tạo hold, đổi giá đã chốt hoặc giữ request thừa sau rời trang. Tối ưu đọc SHALL không bỏ kiểm overlap/giá/revision khi tạo hold thật.

#### Scenario: A customer quickly changes the selected interval
- **WHEN** preview của khoảng cũ trả về sau khoảng mới
- **THEN** UI giữ quote/khoảng mới nhất, không tự giữ sân và không hiển thị giá cũ thay kết quả mới

#### Scenario: A large facility has several years of booking history
- **WHEN** khách mở lịch một sân cho một ngày
- **THEN** không tải toàn lịch sử về browser hoặc đọc từng phút để trình bày; kết quả vẫn phản ánh hold/booking còn hiệu lực và giá hiện hành

### Requirement: Flexible intervals respect effective opening hours

Booking mới SHALL chọn khoảng liên tục từ30 phút trở lên, không giới hạn tối đa (mục33), điểm chọn cách30 phút neo tại đầu khoảng mở cửa hiệu lực, trong cùng ngày địa phương và trong một khoảng mở cửa liên tục. Chính sách đặt giờ SHALL lưu minimumMinutes=30 và maximumMinutes=null. SHALL từ chối dưới30 phút, qua giờ nghỉ, bảo trì, đóng cửa, khoảng đã đặt hoặc hold còn hiệu lực.

#### Scenario: Customer books a single thirty minute step
- **WHEN** sân mở06:00–22:00, khách chọn06:00–06:30 còn trống
- **THEN** hệ thống cho xem quote và giữ đúng khoảng30 phút này

#### Scenario: Customer books a long continuous range
- **WHEN** sân mở06:00–22:00 không có giờ nghỉ, khách chọn09:00–15:00 còn trống
- **THEN** hệ thống báo giá đủ6 giờ và cho giữ chỗ; không có giới hạn240 phút

#### Scenario: Invalid interval is submitted directly
- **WHEN** request dưới30 phút, bắt đầu lệch bước, qua nửa đêm hoặc bắc qua giờ nghỉ
- **THEN** server từ chối dù client đã tự tính số tiền

### Requirement: Pricing partitions the requested interval at every applicable rate boundary

Hệ thống SHALL chọn đơn giá giờ trên từng đoạn bằng cùng precedence cho preview/quote/hold. Mỗi phút phải được phủ giá hợp lệ; thiếu giá, currency khác hoặc hai rule ngang hạng SHALL không sinh quote thanh toán.

#### Scenario: Two rates overlap the requested time
- **WHEN**15–16 có100000đ/giờ và16–17 có200000đ/giờ, khách chọn15:30–16:30
- **THEN** quote có hai đoạn30 phút, tổng150000đ

#### Scenario: Priority changes inside a range
- **WHEN** rule ưu tiên cao hơn chỉ có hiệu lực trên một phần khoảng chọn
- **THEN** chỉ phần đó dùng giá ưu tiên, các đoạn còn lại dùng rule hợp lệ của chúng

### Requirement: Whole VND totals are rounded once

Tổng SHALL là HALF_UP của tổng hourlyRate×minutes chia60, bằng số nguyên VND. SHALL không cộng các số float hoặc làm tròn từng đoạn trước khi tính tổng; amount từng đoạn hiển thị phải cộng đúng tổng.

#### Scenario: Segment boundaries must not create rounding loss
- **WHEN** một giờ101đ được chia thành4 đoạn15 phút bởi các biên rule
- **THEN** tổng vẫn101đ, các số tiền đoạn được phân bổ phần lẻ ổn định và cộng thành101đ

### Requirement: Accepted quotes bind price and interval revisions

Quote SHALL trả interval, timezone, duration, đơn giá/segments, tổng và version/hash. Giữ chỗ SHALL kiểm lại giá, lịch và khoảng trống hiện hành; client amount hoặc quote hết hiệu lực không được thay tiền phải trả.

#### Scenario: Owner changes pricing before hold
- **WHEN** khách gửi expectedAmount/hash cũ sau Owner công bố giá mới
- **THEN** không giữ sân theo giá cũ; trả lỗi thay đổi giá để khách xem lại

### Requirement: Remaining gaps stay bookable without warnings

Vì lượt đặt tối thiểu bằng một bước30 phút, mọi khoảng trống còn lại trên lưới đều đặt được. Quote SHALL không phát cảnh báo khoảng trống lẻ hoặc gợi ý giờ khác; trường warnings/suggestions SHALL vẫn có và rỗng. Một khoảng trống SHALL không tự đổi khoảng/giá, tạo strike hoặc làm từ chối booking.

#### Scenario: The requested example leaves thirty minutes on each side
- **WHEN**15–17 trống, khách chọn15:30–16:30 để lại30 phút hai bên
- **THEN** vẫn được đặt giá150000đ, không có cảnh báo hoặc gợi ý; hai khoảng30 phút còn lại đặt được cho người khác

### Requirement: Legacy per slot prices are not silently reinterpreted

Giá/snapshot cũ SHALL giữ nguyên đơn vị và tiền đã chấp nhận. Theo xác nhận người dùng ngày28/10/2026, báo giá mới khi chưa công bố HOURLY SHALL tính tỷ lệ pricePerSlot / slotMinutes gốc của khoảng mở cửa hiệu lực, không sửa rule đã lưu. Không xác định được slot basis duy nhất SHALL cần Owner chốt giá. Coverage HOURLY đã công bố vẫn dùng giá giờ.

#### Scenario: A legacy court uses thirty minute slots
- **WHEN** rule cũ100000đ/slot và calendar30 phút được nâng cấp
- **THEN** báo giá mới cho60 phút là200000đ, không tự hiểu là100000đ/giờ hoặc sửa tiền booking cũ

### Requirement: Customers choose start and end and see live free ranges
UI SHALL cung cấp giờ bắt đầu/kết thúc theo mốc30 phút và tổng hợp các khoảng trống liên tục, cập nhật mỗi5 giây. Không chọn qua hold/booking/giờ nghỉ; polling không tự giữ sân hay xóa lựa chọn còn hợp lệ.

#### Scenario: Existing hourly slots support a half-hour start
- **WHEN** bảng giá cũ2000đ/60 phút và lịch14:00–16:00 còn trống, khách chọn14:30–15:30
- **THEN** nhận quote2000đ cho đúng khoảng, preview có mốc14:30 và không đổi lịch/tiền của booking đã có

#### Scenario: Another customer holds the selected interval
- **WHEN** polling trả khoảng chọn bị giữ
- **THEN** UI báo khoảng không còn trống và chặn tiếp tục; server vẫn kiểm overlap khi tạo hold

### Requirement: Discovery shows the owner's hourly price

Kết quả tìm sân SHALL hiển thị “Giá thuê từ … /giờ” bằng đơn giá theo giờ Owner thiết lập: pricePerHour của slot nếu có, nếu không thì tiền slot × 60 / số phút của slot, làm tròn tối đa2 chữ số thập phân. Bộ lọc và sắp xếp theo giá SHALL dùng cùng đơn giá giờ. Lưới chọn giờ SHALL vẫn hiển thị tiền của từng ô30 phút.

#### Scenario: A sixty minute price is shown per hour, not per cell
- **WHEN** Owner đặt2000đ cho khung60 phút và lưới hiển thị mỗi ô30 phút1000đ
- **THEN** thẻ tìm sân, bản đồ và trang chủ hiển thị “Từ2.000đ/giờ”; lọc “Giá tối thiểu/giờ =2000” vẫn giữ sân này
