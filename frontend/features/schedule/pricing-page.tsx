'use client'
import { PricingForm } from '@/features/schedule/pricing-form'
import { PricingRules } from '@/features/schedule/pricing-rules'
import { money } from '@/features/schedule/types'
import { usePricing } from '@/features/schedule/use-pricing'
import { displayLabel } from '@/lib/display-labels'
import Link from 'next/link'
import { FlexiblePolicy } from './flexible-policy'
export function PricingPage() {
  const model = usePricing()
  const { basePath, error, rules, courts, categories, message, draft } = model
  return (
    <section className="space-y-6">
      <div className="flex flex-wrap justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold">Bảng giá</h2>
          <p className="mt-2 text-sm text-slate-500">
            Đặt giá theo giờ để khách chọn khoảng chơi từ 30 phút trở lên, theo bước 30 phút. Quy
            tắc theo sân hoặc ngày cụ thể được ưu tiên. Giá theo khung lịch cũ giữ nguyên đơn vị.
          </p>
        </div>
        <Link className="text-sm text-emerald-700 underline" href={`${basePath}/schedule/preview`}>
          Xem trước giá
        </Link>
      </div>
      <FlexiblePolicy facilityId={model.facility.id} />
      <PricingForm key={model.editing?.id ?? 'new'} model={model} />
      {(error || rules.error || courts.error || categories.error) && (
        <p role="alert" className="text-red-700">
          {error || rules.error || courts.error || categories.error}
        </p>
      )}
      {message && (
        <p role="status" className="text-emerald-700">
          {message}
        </p>
      )}
      {rules.loading && <p role="status">Đang tải bảng giá…</p>}
      {rules.data?.length === 0 && (
        <p className="text-slate-500">
          Chưa có quy tắc giá. khung giờ chưa có giá sẽ khóa thao tác đặt.
        </p>
      )}
      {draft && (
        <section className="rounded-xl border p-5">
          <h3 className="font-bold">Giá dự thảo, chưa lưu cấu hình</h3>
          {draft.slots.map((slot) => (
            <p key={slot.startsAt} className="mt-2 text-sm">
              {new Date(slot.startsAt).toLocaleTimeString('vi-VN', { timeZone: draft.timezone })} ·{' '}
              {displayLabel(slot.state)} ·{' '}
              {slot.amount == null ? 'Chưa có giá' : money(slot.amount)}
            </p>
          ))}
        </section>
      )}
      <PricingRules model={model} />
    </section>
  )
}
