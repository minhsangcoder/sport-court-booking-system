'use client'
import dynamic from 'next/dynamic'
import { useApi } from '@/lib/use-api'
import type { PaymentRuntime } from '@/features/payments/types'

const FacilityDemo = dynamic(() =>
  import('./demo-finance').then((module) => module.FacilityDemoMoneyPanel),
)
const FacilityReal = dynamic(() =>
  import('@/features/payments/facility-money-panel').then((module) => module.FacilityMoneyPanel),
)

export function FacilityMoneyPanel(props: { facilityId: string; canReadBooking?: boolean }) {
  const runtime = useApi<PaymentRuntime>('/payments/runtime')
  if (runtime.loading && !runtime.data)
    return (
      <p role="status" className="mt-5">
        Đang tải chế độ thu và chi…
      </p>
    )
  if (runtime.error || !runtime.data)
    return (
      <p role="alert" className="mt-5 text-red-700">
        {runtime.error || 'Chưa xác định được chế độ thanh toán.'}
      </p>
    )
  return runtime.data.mode === 'REAL' ? <FacilityReal {...props} /> : <FacilityDemo {...props} />
}
