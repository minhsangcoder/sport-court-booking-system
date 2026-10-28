import { bookingMessages } from './messages/booking.messages'
import { financeMessages } from './messages/finance.messages'
import { facilityMessages } from './messages/facility.messages'
import { scheduleMessages } from './messages/schedule.messages'
import { bookingOperationsMessages } from './messages/booking-operations.messages'
import { transferMessages } from './messages/transfer.messages'
import { identityMessages } from './messages/identity.messages'
import { commonMessages } from './messages/common.messages'
import { protocolMessages } from './messages/protocol.messages'
/**
 * SportHub error translation utility.
 * Translates backend API error messages and domain codes into natural Vietnamese.
 */
import { displayLabel, fieldLabel } from './display-labels'

/**
 * Exact dictionary mapping English backend messages and codes to Vietnamese.
 */
const EXACT_TRANSLATIONS: Record<string, string> = {
  ...bookingMessages,
  ...financeMessages,
  ...facilityMessages,
  ...scheduleMessages,
  ...bookingOperationsMessages,
  ...transferMessages,
  ...identityMessages,
  ...commonMessages,
  ...protocolMessages,
}

/**
 * Reason translation for slot eligibility issues.
 */
const SLOT_REASON_TRANSLATIONS: Record<string, string> = {
  PRICE_NOT_CONFIGURED: 'Chưa cấu hình bảng giá',
  AMBIGUOUS_PRICE: 'Bảng giá bị trùng lặp hoặc không rõ ràng',
  MAINTENANCE: 'Sân đang trong thời gian bảo trì',
  CLOSED: 'Sân đóng cửa trong khung giờ này',
  BOOKED: 'Khung giờ đã có người đặt',
  HELD: 'Khung giờ đang có người tạm giữ chỗ',
}

/**
 * Translates any error message or code into Vietnamese,
 * Unknown server errors get a Vietnamese fallback. Only user-authored history
 * notes/reasons may opt to preserve an unknown string.
 */
export function translateError(
  message?: string | null,
  options: { code?: string; preserveUnknown?: boolean } = {},
): string {
  if (!message || typeof message !== 'string') return ''

  const trimmed = message.trim()
  if (!trimmed) return ''

  // 1. Direct match in dictionary
  if (Object.hasOwn(EXACT_TRANSLATIONS, trimmed)) {
    return EXACT_TRANSLATIONS[trimmed]
  }

  const codes: Record<string, string> = {
    UNAUTHORIZED: 'Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại.',
    FORBIDDEN: 'Bạn không có quyền thực hiện thao tác này.',
    NOT_FOUND: 'Không tìm thấy dữ liệu yêu cầu.',
    VALIDATION_ERROR: 'Thông tin không hợp lệ. Vui lòng kiểm tra lại.',
    BAD_REQUEST: 'Yêu cầu không hợp lệ. Vui lòng kiểm tra lại thông tin.',
    CONFLICT: 'Dữ liệu đã thay đổi hoặc không còn phù hợp. Vui lòng tải lại và kiểm tra.',
    INTERNAL_ERROR: 'Hệ thống gặp sự cố. Vui lòng thử lại sau.',
    SERVICE_UNAVAILABLE: 'Dịch vụ tạm thời gián đoạn. Vui lòng thử lại sau.',
    RATE_LIMITED: 'Bạn thao tác quá nhanh. Vui lòng đợi một lúc rồi thử lại.',
  }
  if (Object.hasOwn(codes, trimmed)) return codes[trimmed]
  const knownCode = displayLabel(trimmed, '')
  if (knownCode) return knownCode

  // 2. Already in Vietnamese? Check if it contains accented Vietnamese characters
  if (/[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]/i.test(trimmed)) {
    return trimmed.replace(/^BLOCKED_RULE:\s*/, '')
  }

  // 3. Pattern / Prefix matches
  const groupLeadTime = trimmed.match(
    /^Group booking requires at least (\d+) (hours|minutes) before play$/,
  )
  if (groupLeadTime)
    return `Đặt sân theo nhóm cần được tạo trước giờ chơi ít nhất ${groupLeadTime[1]} ${groupLeadTime[2] === 'minutes' ? 'phút' : 'giờ'}. Vui lòng chọn giờ chơi muộn hơn hoặc chuyển sang đặt cá nhân, thanh toán toàn bộ.`

  if (trimmed.startsWith('Slot is not eligible:')) {
    const rawReason = trimmed.replace('Slot is not eligible:', '').trim()
    const viReason = Object.hasOwn(SLOT_REASON_TRANSLATIONS, rawReason)
      ? SLOT_REASON_TRANSLATIONS[rawReason]
      : displayLabel(rawReason, 'Vui lòng chọn giờ chơi khác')
    return `Khung giờ không đủ điều kiện đặt: ${viReason}`
  }

  if (trimmed.startsWith('Missing required parameter:')) {
    const param = trimmed.replace('Missing required parameter:', '').trim()
    return `Thiếu thông tin bắt buộc: ${fieldLabel(param)}`
  }

  if (trimmed.startsWith('Missing facility permission:')) {
    const perm = trimmed.replace('Missing facility permission:', '').trim()
    return `Bạn chưa có quyền thực hiện thao tác này tại cơ sở: ${displayLabel(perm, 'Vui lòng liên hệ chủ cơ sở để được cấp quyền')}.`
  }

  const typeMismatchMatch = trimmed.match(/^Parameter '([^']+)' must be of type (.*)$/)
  if (typeMismatchMatch) {
    return `${fieldLabel(typeMismatchMatch[1])} không đúng định dạng. Vui lòng kiểm tra lại.`
  }

  if (trimmed.startsWith('TECHNICAL_RETRY_EXHAUSTED:')) {
    return 'Đã thử kết nối lại nhiều lần nhưng không thành công. Vui lòng thử lại sau.'
  }

  if (trimmed.startsWith('Review the facility transfer fee and booking refund policy:')) {
    return 'Phí chuyển nhượng hoặc chính sách hoàn đã thay đổi. Vui lòng xem lại và đồng ý điều khoản mới.'
  }
  const size = trimmed.match(/^size must be between (\d+) and (\d+)$/)
  if (size) return `Độ dài phải từ ${size[1]} đến ${size[2]} ký tự.`
  const bound = trimmed.match(/^must be (greater than|less than)( or equal to)? (\d+)$/)
  if (bound)
    return `Giá trị phải ${bound[1] === 'greater than' ? 'lớn' : 'nhỏ'} hơn${bound[2] ? ' hoặc bằng' : ''} ${bound[3]}.`
  if (/^must match /.test(trimmed)) return 'Thông tin không đúng định dạng yêu cầu.'
  const wrapped = trimmed.match(/^\d{3}\s+[A-Z_ ]+:?\s*["'](.+)["']$/)
  if (wrapped) return translateError(wrapped[1], options)

  if (trimmed.startsWith('Supply valid Owner information and first facility')) {
    return 'Vui lòng điền đầy đủ và chính xác thông tin chủ sân và cơ sở thể thao đầu tiên.'
  }

  if (trimmed.includes('Facility review checks failed')) {
    return 'Kiểm tra hồ sơ cơ sở không đạt. Vui lòng hoàn thiện bản nháp (thêm sân, lịch mở cửa, giá, ảnh và 2 tài liệu pháp lý) trước khi gửi thẩm định.'
  }

  if (trimmed.includes('Attach identity and location')) {
    return 'Vui lòng đính kèm ít nhất 2 tài liệu pháp lý (giấy tờ định danh và giấy tờ địa điểm) trước khi gửi thẩm định.'
  }

  if (trimmed.includes('Attach a facility image')) {
    return 'Vui lòng tải lên ít nhất 1 ảnh cơ sở trước khi gửi thẩm định.'
  }

  if (trimmed.includes('Add at least one enabled court')) {
    return 'Vui lòng thêm ít nhất một sân đang bật trước khi gửi thẩm định.'
  }

  if (trimmed.includes('Configure operating hours')) {
    return 'Vui lòng cấu hình lịch mở cửa trước khi gửi thẩm định.'
  }

  if (trimmed.includes('Operating intervals overlap')) {
    return 'Các khoảng thời gian mở cửa bị trùng lặp nhau.'
  }

  if (trimmed.includes('Opening interval must contain whole slots')) {
    return 'Khoảng thời gian mở cửa phải chia thành các khung giờ trọn vẹn.'
  }

  if (trimmed.includes('Price interval must fit')) {
    return 'Khoảng giờ giá phải nằm trọn trong khung giờ mở cửa và khớp với mốc chia khung giờ.'
  }

  if (options.preserveUnknown) return displayLabel(trimmed, trimmed)
  if (options.code && Object.hasOwn(codes, options.code)) return codes[options.code]
  return 'Không thể thực hiện yêu cầu. Vui lòng thử lại hoặc liên hệ cơ sở để được hỗ trợ.'
}
