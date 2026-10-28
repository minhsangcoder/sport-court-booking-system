export const financeMessages: Record<string, string> = {
  'The Owner must configure the facility refund policy before accepting new bookings':
    'Chủ cơ sở cần cấu hình chính sách hoàn tiền trước khi nhận lượt đặt mới.',
  'Owner must configure the facility transfer fee':
    'Chủ cơ sở cần cấu hình phí chuyển nhượng cho cơ sở này.',
  'An explicit facility transfer fee policy is required':
    'Cơ sở cần có chính sách phí chuyển nhượng được chủ cơ sở cấu hình.',
  'Facility refund policy could not be verified':
    'Chưa thể kiểm tra chính sách hoàn tiền của cơ sở. Vui lòng thử lại.',
  'Refund policy version not found': 'Không tìm thấy phiên bản chính sách hoàn tiền.',
  'Refund rate and current policy version are required':
    'Vui lòng nhập tỷ lệ hoàn và tải phiên bản chính sách hiện tại.',
  'Refund rate must be whole basis points': 'Tỷ lệ hoàn chỉ được có tối đa 2 chữ số thập phân.',
  'Refund rate must be between 0 and 10000 basis points': 'Tỷ lệ hoàn phải từ 0% đến 100%.',
  'Transfer fee policy version not found': 'Không tìm thấy phiên bản chính sách phí chuyển nhượng.',
  'Transfer fee rate and current policy version are required':
    'Vui lòng nhập tỷ lệ phí và tải phiên bản chính sách hiện tại.',
  'Transfer fee rate must be whole basis points':
    'Tỷ lệ phí chỉ được có tối đa 2 chữ số thập phân.',
  'Transfer fee rate must be between 0 and 10000 basis points': 'Tỷ lệ phí phải từ 0% đến 100%.',
  'Cancellation key reused with another request':
    'Thao tác hủy không khớp với yêu cầu trước. Vui lòng tải lại.',
  'Cancellation quote does not belong to this request':
    'Báo giá hoàn tiền không thuộc yêu cầu này. Vui lòng xem lại khoản hoàn.',
  'Cancellation allocation changed':
    'Phân bổ khoản hoàn đã thay đổi. Vui lòng xem lại khoản hoàn trước khi hủy.',
  'Resolve the active transfer before cancellation':
    'Cần xử lý tin chuyển nhượng đang hoạt động trước khi hủy lượt đặt.',
  'Legacy cancellation needs an explicit review':
    'Lượt đặt cũ cần được cơ sở rà soát trước khi hủy.',
  'Legacy booking needs an explicit refund policy decision before quoting':
    'Cơ sở cần chốt chính sách hoàn cho lượt đặt cũ trước khi tính khoản hoàn.',
  'Legacy transfer refund needs an explicit review':
    'Khoản hoàn của giao dịch chuyển nhượng cũ cần được cơ sở rà soát.',
  'Applied payment evidence needs review before refund':
    'Cần đối soát bằng chứng thanh toán trước khi hoàn tiền.',
  'Facility refund policy does not support the quoted currency':
    'Chính sách hoàn của cơ sở không hỗ trợ loại tiền trong báo giá.',
  'Booking already has an immutable refund policy':
    'Lượt đặt đã có chính sách hoàn được chốt và không thể thay đổi.',
  'Review the current policy before proposing adoption':
    'Vui lòng xem chính sách hiện tại trước khi đề nghị áp dụng cho lượt đặt cũ.',
  'A different policy adoption has already completed':
    'Lượt đặt này đã chốt một chính sách khác. Vui lòng tải lại.',
  'DEMO settlement not found': 'Không tìm thấy khoản trả người chuyển nhượng mô phỏng.',
  'DEMO refund not found': 'Không tìm thấy khoản hoàn tiền mô phỏng.',
  'Supply a valid Idempotency-Key and DEMO SUCCESS or FAILED outcome':
    'Yêu cầu mô phỏng chưa hợp lệ. Vui lòng tải lại và chọn kết quả thành công hoặc thất bại.',
  'Demo idempotency key payload differs':
    'Nội dung thao tác mô phỏng đã thay đổi. Vui lòng tải lại để kiểm tra kết quả.',
  'DEMO finance handler is unavailable':
    'Chức năng giao dịch mô phỏng tạm thời gián đoạn. Vui lòng thử lại.',
  'DEMO collection and acquisition differ':
    'Khoản thu mô phỏng không khớp với giao dịch nhận chuyển nhượng.',
  'DEMO currency differs': 'Loại tiền của giao dịch mô phỏng không khớp.',
  'DEMO payment identity conflict':
    'Thông tin thanh toán mô phỏng không khớp với giao dịch đã ghi nhận.',
  'DEMO handoff financial identity differs':
    'Thông tin tài chính mô phỏng không khớp với giao dịch bàn giao.',
  'DEMO financial decision does not match handoff':
    'Kết quả tài chính mô phỏng không khớp với kết quả bàn giao.',
  'Unknown DEMO outcome': 'Kết quả giao dịch mô phỏng không hợp lệ.',
  'Positive whole VND amount required': 'Số tiền phải là số nguyên đồng Việt Nam lớn hơn 0.',
  'Amount must be positive and fit NUMERIC(12,2)':
    'Số tiền phải lớn hơn 0 và nằm trong giới hạn cho phép.',
  'Amount must have at most two decimal places': 'Số tiền chỉ được có tối đa 2 chữ số thập phân.',
  'Group contributions are not fully paid': 'Nhóm chưa thanh toán đủ tiền sân.',
  'Group contributions require integral VND amounts':
    'Khoản đóng góp phải là số nguyên đồng Việt Nam.',
  'Group split requires integral VND amounts':
    'Số tiền chia cho thành viên phải là số nguyên đồng Việt Nam.',
  'Allocations must be unique, non-negative integral VND amounts':
    'Mỗi thành viên chỉ có một khoản phân bổ, là số nguyên đồng Việt Nam không âm.',
  'Only successful payments can have refund requests':
    'Chỉ có thể yêu cầu hoàn tiền cho giao dịch đã thanh toán thành công.',
  'Supply a refund review reason of up to 500 characters':
    'Vui lòng nhập lý do rà soát hoàn tiền, tối đa 500 ký tự.',
  'Refund key reused with different input':
    'Yêu cầu hoàn tiền không khớp với lần gửi trước. Vui lòng tải lại.',
  'Refund review not found': 'Không tìm thấy hồ sơ rà soát hoàn tiền.',
  'Refund requires whole VND and an accepted rate':
    'Khoản hoàn phải là số nguyên đồng Việt Nam và có tỷ lệ đã được chấp nhận.',
  'Server acquisition financial terms could not be verified':
    'Chưa thể kiểm tra điều khoản nhận chuyển nhượng. Vui lòng tải lại.',
  'Invalid stored DEMO terms': 'Điều khoản mô phỏng đã lưu không hợp lệ. Cần cơ sở kiểm tra.',
  'Stored financial terms are invalid':
    'Điều khoản tài chính đã lưu không hợp lệ. Cần cơ sở kiểm tra.',
  'Payable amount changed for an existing payment obligation':
    'Số tiền phải trả đã thay đổi. Vui lòng kiểm tra lại giao dịch.',
  'Review and accept the receiving facility, P/F/N and refund policy before publishing':
    'Vui lòng xem và đồng ý cơ sở nhận tiền, giá chuyển nhượng, phí, khoản trả người bán và chính sách hoàn trước khi đăng tin.',
  'Review the listing price and refund policy before acquiring':
    'Vui lòng xem và đồng ý giá chuyển nhượng cùng chính sách hoàn trước khi nhận lượt đặt.',
  'Seller must explicitly accept new financial terms for this legacy listing':
    'Người chuyển nhượng cần đồng ý điều khoản tài chính mới cho tin cũ này.',
  'Legacy acquisition needs review or cancellation before accepting new facility DEMO terms':
    'Giao dịch nhận chuyển nhượng cũ cần được rà soát hoặc hủy trước khi áp dụng điều khoản mô phỏng mới.',
  'New financial acquisitions are paused': 'Tạm dừng tạo giao dịch nhận chuyển nhượng mới.',
  'New financial terms are paused': 'Tạm dừng áp dụng điều khoản tài chính mới.',
  'Only the current booking holder may list a transfer':
    'Chỉ người đang giữ quyền sử dụng lượt đặt mới được đăng chuyển nhượng.',
  'Only paid confirmed future bookings can be transferred':
    'Chỉ được chuyển nhượng lượt đặt đã thanh toán đủ, đã xác nhận và chưa đến giờ chơi.',
  'Transfer price exceeds original booking amount or is not integral VND':
    'Giá chuyển nhượng phải là số nguyên đồng Việt Nam và không vượt quá giá đặt sân ban đầu.',
  'Another buyer already locked this booking':
    'Một người mua khác đang giữ giao dịch nhận chuyển nhượng này.',
  'Transfer listing is no longer active': 'Tin chuyển nhượng không còn hoạt động.',
  'Booking payment is under reconciliation': 'Thanh toán của lượt đặt đang được đối soát.',
  'BLOCKED_RULE: counter operator is not a verified usage owner; link the actual customer before transferring':
    'Người tạo lượt đặt tại quầy không phải người sở hữu. Cần liên kết đúng tài khoản khách trước khi chuyển nhượng.',
  'BLOCKED_RULE: paid group ownership changes need an approved member policy':
    'Chưa thể chuyển nhượng lượt đặt nhóm khi chưa có chính sách thành viên phù hợp.',
  'BLOCKED_RULE: facility paid cancellation requires the 100% refund execution/reconciliation protocol':
    'Lượt đặt đã nhận tiền không hỗ trợ hủy trực tiếp. Liên hệ cơ sở để xử lý yêu cầu hợp lệ.',
  'Booking not found': 'Không tìm thấy lượt đặt sân.',
  'Payment not found': 'Không tìm thấy giao dịch thanh toán.',
  'Listing not found': 'Không tìm thấy tin chuyển nhượng.',
  'Acquisition not found': 'Không tìm thấy giao dịch nhận chuyển nhượng.',
  'Group not found': 'Không tìm thấy nhóm đặt sân.',
  'Hold not found': 'Không tìm thấy lượt giữ chỗ.',
  'User cancelled before payment': 'Khách hủy trước khi thanh toán.',
  'Customer cancelled unpaid hold': 'Khách hủy lượt giữ chỗ chưa thanh toán.',
  'Booking Owner cancelled the unpaid group': 'Người tổ chức hủy lượt đặt nhóm chưa thanh toán.',
  'Session expired': 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
  Unauthorized: 'Vui lòng đăng nhập để tiếp tục.',
  Forbidden: 'Bạn không có quyền thực hiện thao tác này.',
  Required: 'Vui lòng nhập thông tin bắt buộc.',
  'must not be null': 'Vui lòng nhập thông tin bắt buộc.',
  'must not be blank': 'Thông tin này không được để trống.',
  'must not be empty': 'Thông tin này không được để trống.',
  'must be a well-formed email address': 'Vui lòng nhập địa chỉ thư điện tử hợp lệ.',
  'must be a date in the past': 'Vui lòng chọn ngày trong quá khứ.',
  'must be a future date': 'Vui lòng chọn ngày trong tương lai.',
  'must be a date in the present or in the future':
    'Vui lòng chọn thời điểm hiện tại hoặc tương lai.',
  'Không áp dụng hoàn tiền cho người đặt ban đầu khi tự hủy. Chính sách hoàn tiền chỉ áp dụng cho người mua lại lượt đặt qua chuyển nhượng; bạn có thể đăng chuyển nhượng lượt đặt.':
    'Không áp dụng hoàn tiền cho người đặt ban đầu khi tự hủy. Chính sách hoàn tiền chỉ áp dụng cho người mua lại lượt đặt qua chuyển nhượng; quý khách có thể đăng chuyển nhượng lượt đặt.',
  Invalid: 'Thông tin không hợp lệ. Vui lòng kiểm tra lại.',
}
