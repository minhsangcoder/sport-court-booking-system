"use client";
import type { ReactNode } from "react";
import { dateTime, label } from "@/rebuilt/lib/domain";
const names: Record<string, string> = {
  facility: "Cơ sở",
  courts: "Sân",
  documents: "Tài liệu",
  images: "Hình ảnh",
  hours: "Giờ hoạt động",
  prices: "Bảng giá",
  name: "Tên",
  phone: "Điện thoại",
  contactEmail: "Email cơ sở",
  addressLine: "Địa chỉ",
  province: "Tỉnh / Thành phố",
  district: "Quận / Huyện",
  ward: "Phường / Xã",
  timezone: "Múi giờ",
  latitude: "Vĩ độ",
  longitude: "Kinh độ",
  description: "Mô tả",
  amenities: "Tiện ích",
  status: "Trạng thái",
  state: "Trạng thái",
  code: "Mã",
  enabled: "Mở hoạt động",
  dayOfWeek: "Ngày trong tuần",
  opensAt: "Giờ mở cửa",
  closesAt: "Giờ đóng cửa",
  startsAt: "Bắt đầu",
  endsAt: "Kết thúc",
  slotMinutes: "Độ dài khung giờ (phút)",
  pricePerSlot: "Giá mỗi khung giờ",
  priority: "Ưu tiên",
  label: "Tên quy tắc",
  effectiveFrom: "Hiệu lực từ",
  effectiveTo: "Hiệu lực đến",
  currency: "Tiền tệ",
  action: "Hành động",
  actorId: "Người thực hiện",
  actor_id: "Người thực hiện",
  createdAt: "Thời điểm tạo",
  created_at: "Thời điểm tạo",
  occurred_at: "Thời điểm thao tác",
  oldValue: "Trước thay đổi",
  newValue: "Sau thay đổi",
  reason: "Lý do",
  details: "Chi tiết",
  content_type: "Định dạng",
  size_bytes: "Dung lượng (byte)",
  payment_id: "Mã thanh toán",
  policy_reference: "Tham chiếu chính sách",
  amount: "Số tiền",
  transaction_id: "Mã giao dịch",
  provider: "Cổng thanh toán",
  received_at: "Lúc nhận",
  body: "Nội dung callback",
  booking_id: "Mã booking",
  purpose: "Loại thanh toán",
  fingerprint: "Mã đối soát",
  idempotency_key: "Khóa chống lặp",
  payer_id: "Người thanh toán",
  deviceInfo: "Thiết bị",
  ipAddress: "Địa chỉ IP",
  expiresAt: "Hết hạn",
  revoked: "Đã thu hồi",
  before: "Trước",
  after: "Sau",
  changedFields: "Trường đã sửa",
};
export function Records({
  value,
  depth = 0,
}: {
  value: unknown;
  depth?: number;
}): ReactNode {
  if (value === null || value === undefined)
    return <span className="muted">Không ghi nhận</span>;
  if (Array.isArray(value))
    return value.length ? (
      <div className="stack" style={{ gap: 14 }}>
        {value.map((item, i) => (
          <div className="record" key={i}>
            <Records value={item} depth={depth + 1} />
          </div>
        ))}
      </div>
    ) : (
      <p className="muted small">Chưa có bản ghi</p>
    );
  if (typeof value === "object")
    return (
      <dl className="detail-list">
        {Object.entries(value as Record<string, unknown>)
          .filter(
            ([key]) =>
              ![
                "ownerId",
                "objectKey",
                "object_key",
                "private_payload",
              ].includes(key),
          )
          .map(([key, v]) => (
            <div
              key={key}
              style={{
                display:
                  typeof v === "object" && v !== null ? "block" : undefined,
              }}
            >
              <dt>{names[key] ?? key}</dt>
              <dd
                style={{
                  maxWidth: typeof v === "object" ? "100%" : undefined,
                  textAlign: typeof v === "object" ? "left" : undefined,
                  marginTop: typeof v === "object" ? 10 : undefined,
                }}
              >
                <Records value={v} depth={depth + 1} />
              </dd>
            </div>
          ))}
      </dl>
    );
  if (typeof value === "boolean") return value ? "Có" : "Không";
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}T/.test(value))
    return dateTime(value);
  return label(String(value));
}
