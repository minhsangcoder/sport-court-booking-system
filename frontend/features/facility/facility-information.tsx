'use client'
import type { useFacilityOverview } from '@/features/facility/use-facility-overview'
import { timezoneLabel } from '@/lib/display-labels'
import { ClipboardList, Clock, Mail, MapPin, Phone } from 'lucide-react'
import Link from 'next/link'
export function FacilityInformation({
  model,
}: {
  model: Pick<ReturnType<typeof useFacilityOverview>, 'basePath' | 'facility' | 'applicationId'>
}) {
  const { basePath, facility, applicationId } = model
  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <div className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-sm lg:col-span-2 dark:border-slate-800 dark:bg-slate-900">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-bold text-slate-900 dark:text-white">
            Thông tin liên hệ & Giới thiệu
          </h3>
          <Link
            href={`${basePath}/edit`}
            className="text-xs font-bold text-emerald-600 hover:underline"
          >
            Sửa thông tin
          </Link>
        </div>
        <p className="text-sm leading-relaxed text-slate-600 dark:text-slate-300">
          {facility.description || 'Chưa cập nhật mô tả chi tiết cho cơ sở.'}
        </p>

        <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 border-t border-slate-100 pt-5 dark:border-slate-800 text-xs">
          <div className="flex items-start gap-2.5">
            <MapPin size={16} className="text-slate-400 shrink-0 mt-0.5" />
            <div>
              <span className="font-bold text-slate-700 dark:text-slate-200">Địa chỉ:</span>
              <p className="text-slate-500">
                {facility.addressLine}, {facility.ward}, {facility.province}
              </p>
            </div>
          </div>
          <div className="flex items-start gap-2.5">
            <Phone size={16} className="text-slate-400 shrink-0 mt-0.5" />
            <div>
              <span className="font-bold text-slate-700 dark:text-slate-200">Số hỗ trợ:</span>
              <p className="text-slate-500">{facility.phone}</p>
            </div>
          </div>
          <div className="flex items-start gap-2.5">
            <Mail size={16} className="text-slate-400 shrink-0 mt-0.5" />
            <div>
              <span className="font-bold text-slate-700 dark:text-slate-200">
                Thư điện tử cơ sở:
              </span>
              <p className="text-slate-500">{facility.contactEmail || 'Chưa cung cấp'}</p>
            </div>
          </div>
          <div className="flex items-start gap-2.5">
            <Clock size={16} className="text-slate-400 shrink-0 mt-0.5" />
            <div>
              <span className="font-bold text-slate-700 dark:text-slate-200">Múi giờ:</span>
              <p className="text-slate-500">{timezoneLabel(facility.timezone)}</p>
            </div>
          </div>
        </div>

        {/* Amenities Pills */}
        <div className="mt-6 border-t border-slate-100 pt-5 dark:border-slate-800">
          <span className="text-xs font-bold text-slate-700 dark:text-slate-200 block mb-2">
            Tiện ích phục vụ người chơi:
          </span>
          <div className="flex flex-wrap gap-2">
            {facility.amenities.map((a) => (
              <span
                key={a}
                className="rounded-lg bg-emerald-50 px-3 py-1 text-xs font-bold text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
              >
                ✓ {a}
              </span>
            ))}
            {facility.amenities.length === 0 && (
              <span className="text-xs text-slate-400">Chưa khai báo tiện ích nào.</span>
            )}
          </div>
        </div>
      </div>

      <div className="rounded-xl border border-slate-200/90 bg-white p-5 shadow-2xs dark:border-slate-800 dark:bg-slate-900 flex flex-col justify-between">
        <div>
          <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-emerald-700">
            <ClipboardList size={13} strokeWidth={1.75} />
            <span>Quy trình vận hành</span>
          </div>
          <h4 className="mt-2 text-base font-bold text-slate-900 dark:text-white">
            Hướng dẫn mở bán sân hiệu quả
          </h4>
          <ul className="mt-4 space-y-3 text-xs text-slate-500 dark:text-slate-400">
            <li className="flex items-start gap-2">
              <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-[10px] font-bold text-emerald-800">
                1
              </span>
              <span>Thêm tối thiểu 1 sân và cấu hình kích hoạt</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-[10px] font-bold text-emerald-800">
                2
              </span>
              <span>Cài đặt giờ mở cửa trong tuần và bảng giá theo giờ</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-[10px] font-bold text-emerald-800">
                3
              </span>
              <span>Tải lên ít nhất 2 ảnh thực tế để khách dễ hình dung</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-[10px] font-bold text-emerald-800">
                4
              </span>
              <span>Gửi thẩm định hồ sơ để kích hoạt trạng thái mở bán</span>
            </li>
          </ul>
        </div>
        <Link
          href={applicationId ? `/owner/application/${applicationId}` : `${basePath}/review`}
          className="mt-6 flex items-center justify-center gap-1.5 rounded-xl bg-slate-900 py-2.5 text-xs font-bold text-white shadow-sm hover:bg-slate-800 dark:bg-emerald-600 dark:hover:bg-emerald-500"
        >
          <span>Kiểm tra tiến độ thẩm định</span>
        </Link>
      </div>
    </div>
  )
}
