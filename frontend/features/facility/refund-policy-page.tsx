'use client'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ApiError, api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
import { useFacility } from './facility-store'
import { refundBpsToPercent, refundPercentToBps, type RefundPolicy } from './refund-policy'

export function RefundPolicyPage() {
  const { facility } = useFacility()
  return <RefundPolicyEditor key={facility.id} facilityId={facility.id} />
}

function RefundPolicyEditor({ facilityId }: { facilityId: string }) {
  const path = `/owner/facilities/${facilityId}/refund-policy`
  const resource = useApi<RefundPolicy>(path)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const [saved, setSaved] = useState(false)

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!resource.data || busy) return
    const form = new FormData(event.currentTarget)
    setError('')
    setConflict(false)
    setSaved(false)
    try {
      const rateBps = refundPercentToBps(String(form.get('percent') ?? ''))
      setBusy(true)
      await api<RefundPolicy>(path, {
        method: 'PUT',
        body: JSON.stringify({ rateBps, version: resource.data.version }),
      })
      setSaved(true)
      resource.reload()
    } catch (failure) {
      const changed = failure instanceof ApiError && failure.status === 409
      setConflict(changed)
      setError(
        changed
          ? 'Chính sách đã thay đổi. Tải bản mới trước khi lưu.'
          : failure instanceof Error
            ? failure.message
            : 'Không thể lưu chính sách hoàn tiền.',
      )
    } finally {
      setBusy(false)
    }
  }

  if (resource.loading) return <p role="status">Đang tải chính sách hoàn tiền…</p>
  if (!resource.data)
    return (
      <div role="alert" className="space-y-3 text-red-700">
        <p>{resource.error || 'Chưa tải được chính sách hoàn tiền.'}</p>
        <Button onClick={resource.reload}>Thử lại</Button>
      </div>
    )
  const policy = resource.data
  return (
    <section className="max-w-2xl space-y-5">
      <h2 className="text-2xl font-bold">Chính sách hoàn tiền</h2>
      <p className="text-slate-600">
        Cấu hình tỷ lệ hoàn tiền khi lượt đặt được hủy hợp lệ trước giờ chơi.{' '}
        <strong>Lưu ý:</strong> Chính sách hoàn tiền này chỉ áp dụng để hoàn tiền cho người mua lại
        lượt đặt qua chuyển nhượng (dựa trên giá mua lại × tỷ lệ hoàn của cơ sở). Lượt đặt trực tiếp
        ban đầu khi khách tự hủy sẽ không được hoàn tiền.
      </p>
      {!policy.configured && (
        <p className="rounded-xl bg-amber-50 p-4 text-amber-900">
          Cơ sở chưa cấu hình tỷ lệ hoàn nên chưa nhận lượt đặt mới. Hãy lưu chính sách để mở đặt
          sân; có thể chọn rõ 0% nếu cơ sở không hoàn khi hủy.
        </p>
      )}
      {policy.configured && (
        <p className="text-sm text-slate-600">
          Tỷ lệ hiện tại: <strong>{refundBpsToPercent(policy.rateBps)}%</strong> · Phiên bản{' '}
          {policy.version}
        </p>
      )}
      <form
        key={`${policy.version}:${policy.rateBps}`}
        onSubmit={submit}
        className="space-y-4 rounded-2xl border p-5"
      >
        <label className="grid gap-2 text-sm font-medium">
          Tỷ lệ hoàn (%)
          <Input
            name="percent"
            inputMode="decimal"
            required
            autoComplete="off"
            placeholder="Ví dụ: 80"
            defaultValue={refundBpsToPercent(policy.rateBps)}
            disabled={busy}
            aria-describedby="refund-rate-help"
          />
        </label>
        <p id="refund-rate-help" className="text-sm text-slate-500">
          Từ 0 đến 100%, tối đa 2 chữ số thập phân. Chỉ chủ cơ sở được thay đổi tỷ lệ.
        </p>
        {error && (
          <p role="alert" className="text-red-700">
            {error}
          </p>
        )}
        {conflict && (
          <Button
            type="button"
            onClick={() => {
              setError('')
              setConflict(false)
              resource.reload()
            }}
          >
            Tải chính sách mới
          </Button>
        )}
        {saved && (
          <p role="status" className="text-emerald-700">
            Đã lưu chính sách hoàn tiền cho lượt đặt mới.
          </p>
        )}
        <Button type="submit" disabled={busy || conflict} className="bg-emerald-600 text-white">
          {busy ? 'Đang lưu…' : 'Lưu chính sách hoàn tiền'}
        </Button>
      </form>
      <div className="space-y-2 rounded-xl bg-slate-50 p-4 text-sm text-slate-600">
        <p>
          Chủ cơ sở hoặc nhân viên được cấp quyền hoàn tiền tại cơ sở thực hiện khoản hoàn. Nhân
          viên không được sửa tỷ lệ hoặc chọn người nhận khác.
        </p>
        <p>
          Với lượt đặt đã chuyển nhượng, tỷ lệ tính trên giá người giữ hiện tại đã mua lại và hoàn
          cho người đó. Tiền gốc và khoản trả người bán được đối soát riêng.
        </p>
        <p>
          Lỗi thanh toán hoặc bàn giao chuyển nhượng không thành công được hoàn đủ 100% khoản đã
          thu; không áp tỷ lệ hủy này.
        </p>
      </div>
    </section>
  )
}
