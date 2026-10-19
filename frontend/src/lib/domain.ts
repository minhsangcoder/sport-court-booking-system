// Wire models checked against service DTOs and migrations, 2026-10-12.
export type Facility = {
  id: string;
  ownerId: string;
  name: string;
  phone: string;
  contactEmail?: string | null;
  addressLine: string;
  provinceCode?: string | null;
  province: string;
  district: string;
  wardCode?: string | null;
  ward: string;
  description: string | null;
  timezone: string;
  latitude: number | null;
  longitude: number | null;
  status: string;
  amenities: string[];
  version: number;
};
export type Court = {
  id: string;
  facilityId: string;
  sportCategoryId: string;
  code: string;
  name: string;
  description: string | null;
  enabled: boolean;
  version: number;
};
export type Category = { id: string; name: string; active: boolean };
export type Media = {
  id: string;
  courtId: string | null;
  url: string;
  contentType: string;
  sizeBytes: number;
};
export type DocumentFile = {
  id: string;
  name: string;
  url: string;
  contentType: string;
  sizeBytes: number;
  archived: boolean;
};
export type Slot = {
  startsAt: string;
  endsAt: string;
  state: string;
  reason: string | null;
  amount: number | null;
  currency: string;
  ruleId: string | null;
  ruleVersion: number;
  ruleLabel: string | null;
};
export type Preview = {
  facilityId: string;
  courtId: string;
  date: string;
  timezone: string;
  slots: Slot[];
};
export type Quote = {
  facilityId: string;
  courtId: string;
  startsAt: string;
  endsAt: string;
  amount: number;
  currency: string;
  timezone: string;
  segments: Slot[];
};
export type Hold = {
  id: string;
  courtId: string;
  facilityId: string;
  holderId: string;
  startsAt: string;
  endsAt: string;
  expiresAt: string;
  state: string;
  quote: Quote;
};
export type Booking = {
  id: string;
  facilityId: string;
  courtId: string;
  customerId: string;
  currentHolderId: string;
  createdBy: string;
  startsAt: string;
  endsAt: string;
  status: string;
  source: string;
  guestName: string | null;
  guestPhone: string | null;
  amount: number;
  currency: string;
  priceSnapshot: Quote;
  holdExpiresAt: string;
  paymentId: string | null;
  paidAt: string | null;
  checkedInAt: string | null;
  completedAt: string | null;
  version: number;
};
export type BookingDetail = {
  booking: Booking;
  history: {
    id: string;
    action: string;
    actorId: string | null;
    details: unknown;
    createdAt: string;
  }[];
  checkinToken: string | null;
  checkinQrSvg: string | null;
};
export type Payment = {
  id: string;
  bookingId: string;
  payerId: string;
  memberId: string | null;
  acquisitionId: string | null;
  purpose: string;
  amount: number;
  currency: string;
  provider: string;
  providerReference: string;
  status: string;
  expiresAt: string;
  paidAt: string | null;
};
export type DiscoveryItem = {
  facility: Pick<
    Facility,
    | "id"
    | "name"
    | "addressLine"
    | "provinceCode"
    | "province"
    | "district"
    | "wardCode"
    | "ward"
    | "timezone"
    | "amenities"
    | "latitude"
    | "longitude"
  >;
  courtId: string;
  courtName: string;
  sportCategoryId: string;
  sportName: string;
  date: string;
  fromPrice: number;
  currency: string;
  availableSlots: number;
  firstStartsAt: string;
  firstEndsAt: string;
  distanceKm: number | null;
};
export type SearchResult = { items: DiscoveryItem[]; truncated: boolean };
export type MatchingCourt = {
  courtId: string; courtName: string; sportCategoryId: string; sportName: string;
  fromPrice: number; currency: string; availableSlots: number;
  firstStartsAt: string; firstEndsAt: string; slots: Slot[];
};
export type FacilityDiscoveryItem = {
  facility: DiscoveryItem["facility"]; imageUrl: string | null; date: string;
  matchingCourts: number; sports: { id: string; name: string }[];
  fromPrice: number; currency: string; availableSlots: number;
  distanceKm: number | null; courts: MatchingCourt[];
};
export type FacilitySearchResult = {
  items: FacilityDiscoveryItem[]; page: number; size: number;
  totalFacilities: number; totalPages: number;
};
export type Member = {
  id: string;
  userId: string;
  displayName: string;
  active: boolean;
  amountDue: number;
  amountPaid: number;
  paymentState: string;
  paymentRequestedAt: string | null;
  lastReminderAt: string | null;
  nextReminderAt: string | null;
  reminderEligible: boolean;
  reminderBlockedReason: string | null;
};
export type Group = {
  id: string;
  ownerId: string;
  name: string;
  state: string;
  deadline: string;
  maxMembers: number;
  allocationsLocked: boolean;
  booking: Booking;
  members: Member[];
  totalPaid: number;
};
export type Invite = {
  code: string;
  path: string;
  qrSvg: string;
  qrPngDataUrl?: string;
  expiresAt: string;
};
export type Listing = {
  id: string;
  facilityId: string;
  courtId: string;
  venue: {
    facilityName: string;
    courtName: string;
    sportCategoryId: string;
    address: string;
    province: string;
    district: string;
    timezone: string;
    amenities: string[];
  };
  originalAmount: number;
  price: number;
  currency: string;
  startsAt: string;
  endsAt: string;
  deadline: string;
  state: string;
  own: boolean;
  available: boolean;
  version: number;
};
export type Acquisition = {
  id: string;
  listingId: string;
  bookingId: string;
  buyerId: string;
  amount: number;
  currency: string;
  expiresAt: string;
  state: string;
  paymentId: string | null;
};
export type Hours = {
  id: string;
  facilityId: string;
  courtId: string | null;
  dayOfWeek: number;
  opensAt: string;
  closesAt: string;
  slotMinutes: number;
  version: number;
};
export type ScheduleException = {
  id: string;
  courtId: string | null;
  date: string;
  type: "CLOSED" | "SPECIAL_HOURS";
  opensAt: string | null;
  closesAt: string | null;
  reason: string;
};
export type PriceRule = {
  id: string;
  courtId: string | null;
  sportCategoryId: string | null;
  dayOfWeek: number | null;
  specificDate: string | null;
  startsAt: string;
  endsAt: string;
  pricePerSlot: number;
  priority: number;
  label: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  currency: string;
  active: boolean;
  version: number;
};
export type Maintenance = {
  id: string;
  courtId: string;
  startsAt: string;
  endsAt: string;
  reason: string;
  cancelled: boolean;
};
export type Binding = {
  id: string;
  facilityId: string;
  userId: string;
  fullName: string;
  email: string | null;
  active: boolean;
  permissions: string[];
};
export type Access = {
  facilityBindings: { facilityId: string; permissions: string[] }[];
};
export type Legal = {
  representativeName: string;
  identityNumber: string;
  businessName: string;
  businessLicense: string | null;
  taxCode: string | null;
  bankName: string;
  bankAccountHolder: string;
  bankAccountNumber: string;
};
export type OwnerApplication = {
  id: string;
  userId: string;
  facilityId: string;
  businessName: string;
  facilityName: string;
  state: string;
  submittedAt: string | null;
  reason: string | null;
  commissionPercent: number | null;
  lastError: string | null;
};
export type FacilityReview = {
  id: string;
  state: string;
  submittedAt: string;
  reason: string | null;
  snapshot: unknown;
};
export type FacilityDetail = {
  facility: Facility;
  courts: Court[];
  reviews: FacilityReview[];
  audit: {
    id: string;
    action: string;
    occurred_at: string;
    details: string | null;
  }[];
};
export type ApplicationDetail = {
  application: OwnerApplication;
  legal: Legal;
  applicant?: {
    id: string;
    fullName: string;
    email: string | null;
    phone: string | null;
    status: string;
  };
  facility: FacilityDetail;
  history: { id: string; submittedAt: string; facilitySnapshot: unknown }[];
  reviewSnapshot?: unknown;
};
export type Account = {
  id: string;
  fullName: string;
  email: string | null;
  phone: string | null;
  roles: import("./api").Role[];
  status: string;
  createdAt: string;
  lastLoginAt: string | null;
  lockReason: string | null;
  lockedUntil: string | null;
};
export const weekdays = [
  "Thứ hai",
  "Thứ ba",
  "Thứ tư",
  "Thứ năm",
  "Thứ sáu",
  "Thứ bảy",
  "Chủ nhật",
];
export const labels: Record<string, string> = {
  AVAILABLE: "Còn trống",
  ELIGIBLE: "Đủ điều kiện theo cấu hình",
  BLOCKED: "Không thể phục vụ",
  BLOCKED_RULE: "Chờ hoàn thiện chính sách",
  CLOSED_EXCEPTION: "Nghỉ theo lịch ngoại lệ",
  COURT_DISABLED: "Sân tạm ngừng",
  PRICE_NOT_CONFIGURED: "Chưa cấu hình giá",
  AMBIGUOUS_PRICE: "Quy tắc giá bị trùng ưu tiên",
  PAST_SLOT: "Đã qua giờ",
  BOOKING: "Đặt sân",
  GROUP_CONTRIBUTION: "Đóng góp nhóm",
  TRANSFER: "Nhận chuyển nhượng",
  HOLD: "Đang giữ chỗ",
  HELD: "Đang giữ chỗ",
  BOOKED: "Đã đặt",
  MAINTENANCE: "Bảo trì",
  CLOSED: "Đóng cửa",
  NO_PRICE: "Chưa có giá",
  DISABLED: "Tạm ngừng",
  ACTIVE: "Đang hoạt động",
  DRAFT: "Bản nháp",
  PENDING: "Chờ thanh toán",
  CONFIRMED: "Đã xác nhận",
  CHECKED_IN: "Đang sử dụng",
  COMPLETED: "Hoàn tất",
  CANCELLED: "Đã hủy",
  EXPIRED: "Hết hạn",
  SUCCESS: "Thành công",
  FAILED: "Thất bại",
  PENDING_APPROVAL: "Chờ thẩm định",
  SUPPLEMENT_REQUIRED: "Cần bổ sung",
  APPROVED: "Đã phê duyệt",
  REJECTED: "Đã từ chối",
  SUBMITTING: "Đang gửi hồ sơ",
  APPROVING: "Đang kích hoạt",
  DECIDING: "Đang lưu quyết định",
  GROUP_PENDING: "Đang nhận đóng góp",
  GROUP_EXPIRED: "Hết hạn đóng góp",
  GROUP_CANCELLED: "Đã hủy nhóm",
  UNPAID: "Chưa phân bổ",
  WAITING_FOR_PAYMENT: "Chờ đóng góp",
  PAID: "Đã thanh toán",
  PAID_BY_OWNER: "Đã trả thay",
  LOCKED: "Đang khóa",
  WITHDRAWN: "Đã rút tin",
  PENDING_HANDOFF: "Đang bàn giao",
  PENDING_AUDIT: "Cần đối soát",
  PENDING_VERIFICATION: "Chờ xác minh",
  SUSPENDED: "Tạm khóa",
  CUSTOMER: "Người chơi",
  OWNER: "Chủ sân",
  STAFF: "Nhân viên",
  ADMIN: "Quản trị viên",
  BOOKING_CREATED: "Tạo lượt đặt",
  PAYMENT_CONFIRMED: "Xác nhận thanh toán",
  GROUP_CONFIRMED: "Nhóm đã thanh toán đủ",
  TRANSFER_COMPLETED: "Chuyển giao quyền sử dụng",
  BOOKING_CANCELLED: "Hủy lượt đặt",
  BOOKING_CHECKED_IN: "Check-in",
  BOOKING_COMPLETED: "Hoàn tất sử dụng",
  HELD_POLICY_BLOCKED: "Đang giữ tiền; chưa chi trả",
};
export const label = (state: string) =>
  Object.prototype.hasOwnProperty.call(labels, state) ? labels[state] : state;
export const money = (amount: number, currency = "VND") =>
  new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
  }).format(amount);
export const today = () =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
export const time = (value: string, zone = "Asia/Ho_Chi_Minh") =>
  new Intl.DateTimeFormat("vi-VN", {
    hour: "2-digit",
    minute: "2-digit",
    timeZone: zone,
  }).format(new Date(value));
export const dateTime = (value: string, zone = "Asia/Ho_Chi_Minh") =>
  new Intl.DateTimeFormat("vi-VN", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: zone,
  }).format(new Date(value));
export const shortId = (value: string) => value.slice(0, 8).toUpperCase();
export const localInstant = (value: string) =>
  new Date(value + "+07:00").toISOString();
