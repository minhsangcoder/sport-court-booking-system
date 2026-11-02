'use client'
import {
  OwnerPageHead,
  emeraldNotice,
  emptyPanel,
  outlinePill,
  redNotice,
} from '@/features/facility/owner-ui'
import { PricingForm } from '@/features/schedule/pricing-form'
import { PricingRules } from '@/features/schedule/pricing-rules'
import { usePricing } from '@/features/schedule/use-pricing'
import { Eye } from 'lucide-react'
import Link from 'next/link'
import { FlexiblePolicy } from './flexible-policy'

export function PricingPage() {
  const model = usePricing()
  const { basePath, error, rules, courts, categories, message } = model

  return (
    <section className="space-y-6">
      <OwnerPageHead
        title="Bảng giá"
        description="Đặt giá theo giờ để khách chọn khoảng chơi từ 30 phút trở lên, theo bước 30 phút. Quy tắc theo sân hoặc ngày cụ thể được ưu tiên. Giá theo khung lịch cũ giữ nguyên đơn vị."
        actions={
          <Link className={outlinePill} href={`${basePath}/schedule/preview`}>
            <Eye className="size-4" />
            Xem trước giá
          </Link>
        }
      />

      <FlexiblePolicy facilityId={model.facility.id} />

      {(error || rules.error || courts.error || categories.error) && (
        <p role="alert" className={redNotice}>
          {error || rules.error || courts.error || categories.error}
        </p>
      )}

      {message && (
        <p role="status" className={emeraldNotice}>
          {message}
        </p>
      )}

      <PricingForm key={model.editing?.id ?? 'new'} model={model} />

      {rules.loading && (
        <p role="status" className="text-sm text-slate-500">
          Đang tải bảng giá…
        </p>
      )}

      {rules.data?.length === 0 && (
        <div className={emptyPanel}>
          Chưa có quy tắc giá. Khung giờ chưa có giá sẽ khóa thao tác đặt.
        </div>
      )}

      <PricingRules model={model} />
    </section>
  )
}
