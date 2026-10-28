'use client'
import { useRef, useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { BankSelect } from '@/features/payments/bank-select'
import { useFacility } from './facility-store'

type Channel = {
  channelId: string
  provider: string
  version: number
  status: string
  bankBin: string
  maskedAccountNumber: string
  accountName: string
  webhookUrl: string
}
export function SepayChannelPage() {
  const { facility } = useFacility()
  const resource = useApi<Channel | null>(`/payments/facilities/${facility.id}/sepay-channel`)
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [message, setMessage] = useState('')
  const intent = useRef<{ body: string; key: string } | null>(null)
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy || resource.loading || resource.error) return
    const form = event.currentTarget,
      fields = new FormData(form)
    const body = JSON.stringify({
      version: resource.data?.status === 'DISABLED' ? 0 : (resource.data?.version ?? 0),
      bankBin: fields.get('bankBin'),
      accountNumber: fields.get('accountNumber'),
      accountName: fields.get('accountName'),
      webhookApiKey: fields.get('webhookApiKey'),
    })
    if (intent.current?.body !== body) intent.current = { body, key: crypto.randomUUID() }
    setBusy(true)
    setError('')
    setMessage('')
    try {
      await api(`/payments/facilities/${facility.id}/sepay-channel`, {
        method: 'PUT',
        headers: { 'Idempotency-Key': intent.current.key },
        body,
      })
      intent.current = null
      form.reset()
      await resource.reload()
      setMessage(
        'Đã lưu kênh SePay. Thiết lập webhook Có tiền vào trên SePay bằng địa chỉ bên dưới và cùng khóa API vừa nhập. Đơn chỉ được xác nhận sau khi nhận thông báo hợp lệ, đủ tiền.',
      )
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Không thể lưu kênh SePay.')
      void resource.reload()
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="max-w-2xl space-y-5">
      <h1 className="text-2xl font-bold">Kênh nhận tiền SePay</h1>
      <p className="text-sm text-slate-600">
        Khách đặt sân chuyển tiền vào tài khoản cơ sở. Khi SePay báo đủ tiền, hệ thống mới xác nhận
        lượt đặt. Người mua chuyển nhượng trả trực tiếp người bán; cơ sở không thu phí chuyển
        nhượng.
      </p>
      {resource.loading && <p role="status">Đang tải kênh nhận tiền…</p>}
      {!resource.loading && !resource.error && !resource.data && (
        <p role="status" className="rounded-xl bg-amber-50 p-4 text-sm text-amber-900">
          Cơ sở chưa có tài khoản nhận tiền. Nhập thông tin bên dưới để tạo QR SePay cho khách đặt
          sân. Đây là tài khoản của cơ sở; khách đặt sân không cần khai tài khoản ngân hàng.
        </p>
      )}
      {(error || resource.error) && (
        <p role="alert" className="rounded-xl bg-red-50 p-4 text-red-700">
          {error || resource.error}
          {resource.error && (
            <button type="button" className="ml-2 underline" onClick={resource.reload}>
              Tải lại kênh nhận tiền
            </button>
          )}
        </p>
      )}
      {message && (
        <p role="status" className="rounded-xl bg-emerald-50 p-4 text-emerald-800">
          {message}
        </p>
      )}
      {resource.data && (
        <div className="space-y-2 rounded-xl border p-4 text-sm">
          <p>
            {resource.data.provider === 'SEPAY'
              ? 'Đã cấu hình SePay'
              : 'Kênh cũ cần thay bằng SePay'}{' '}
            · {resource.data.maskedAccountNumber} · {resource.data.accountName}
          </p>
          {resource.data.provider === 'SEPAY' && (
            <>
              <p className="break-all">Webhook: {resource.data.webhookUrl}</p>
              <p>
                Trong SePay: chọn sự kiện Có tiền vào, đúng tài khoản ngân hàng, phương thức API
                Key; đặt tiền tố mã thanh toán SC nếu sử dụng bộ lọc.
              </p>
            </>
          )}
        </div>
      )}
      <form onSubmit={save} autoComplete="off" className="space-y-4 rounded-xl border p-5">
        <BankSelect
          key={resource.data?.version ?? 0}
          defaultValue={resource.data?.bankBin}
          disabled={busy || resource.loading || Boolean(resource.error)}
        />
        <label className="grid gap-2 text-sm">
          Số tài khoản cơ sở
          <Input
            name="accountNumber"
            required
            pattern="[0-9]{1,19}"
            maxLength={19}
            inputMode="numeric"
            disabled={busy}
          />
        </label>
        <label className="grid gap-2 text-sm">
          Tên người nhận
          <Input name="accountName" required maxLength={100} disabled={busy} />
        </label>
        <label className="grid gap-2 text-sm">
          Khóa API xác thực webhook SePay
          <Input
            name="webhookApiKey"
            type="password"
            autoComplete="new-password"
            required
            minLength={32}
            maxLength={200}
            pattern="[A-Za-z0-9_.:\-]{32,200}"
            disabled={busy}
          />
        </label>
        <p className="text-xs text-slate-500">
          Nhập cùng khóa API bạn cấu hình cho webhook SePay, tối thiểu 32 ký tự. Khóa được mã hóa ở
          máy chủ và không trả lại khi đọc thông tin kênh. SePay cần gọi được địa chỉ HTTPS công
          khai; localhost chỉ phục vụ phát triển.
        </p>
        <Button type="submit" disabled={busy || resource.loading || Boolean(resource.error)}>
          {busy ? 'Đang lưu…' : 'Lưu kênh SePay'}
        </Button>
      </form>
    </section>
  )
}
