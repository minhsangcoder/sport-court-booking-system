'use client'
import { useRef, useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { BankName, BankSelect } from '@/features/payments/bank-select'
import { ExternalLink, KeyRound, Pencil, RefreshCw } from 'lucide-react'
import { CopyButton } from './copy-button'
import { useFacility } from './facility-store'
import {
  OwnerPageHead,
  StepTitle,
  amberNotice,
  card,
  emeraldNotice,
  fieldLabel,
  outlinePill,
  primaryPill,
  redNotice,
} from './owner-ui'

type Channel = {
  channelId: string
  provider: string
  version: number
  status: string
  bankBin: string
  maskedAccountNumber: string
  accountName: string
  webhookUrl: string
  lastWebhookAt: string | null
}

function generateWebhookKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

const stepCard = `${card} home-rise max-w-3xl space-y-4 p-5 sm:p-6`

export function SepayChannelPage() {
  const { facility } = useFacility()
  const resource = useApi<Channel | null>(`/payments/facilities/${facility.id}/sepay-channel`)
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [message, setMessage] = useState(''),
    [editing, setEditing] = useState(false),
    [generated, setGenerated] = useState(''),
    [savedKey, setSavedKey] = useState('')
  const intent = useRef<{ body: string; key: string } | null>(null)
  const formRef = useRef<HTMLFormElement | null>(null)
  const channel = resource.data
  const configured = channel?.provider === 'SEPAY' && channel.status !== 'DISABLED'
  const showForm = !resource.loading && !resource.error && (!configured || editing)

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy || resource.loading || resource.error) return
    const form = event.currentTarget,
      fields = new FormData(form),
      webhookApiKey = fields.get('webhookApiKey')
    const body = JSON.stringify({
      version: channel?.status === 'DISABLED' ? 0 : (channel?.version ?? 0),
      bankBin: fields.get('bankBin'),
      accountNumber: fields.get('accountNumber'),
      accountName: fields.get('accountName'),
      webhookApiKey,
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
      setEditing(false)
      setGenerated('')
      setSavedKey(String(webhookApiKey))
      await resource.reload()
      setMessage('Đã lưu tài khoản. Làm tiếp bước 3 để SePay gửi được thông báo về hệ thống.')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Không thể lưu kênh SePay.')
      void resource.reload()
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="space-y-5">
      <OwnerPageHead
        title="Kênh nhận tiền SePay"
        description="Khách đặt sân chuyển tiền vào tài khoản cơ sở. Khi SePay báo đủ tiền, hệ thống mới xác nhận lượt đặt. Người mua chuyển nhượng trả trực tiếp người bán; cơ sở không thu phí chuyển nhượng."
      />
      {resource.loading && <p role="status">Đang tải kênh nhận tiền…</p>}
      {!resource.loading && !resource.error && !channel && (
        <p role="status" className={`${amberNotice} max-w-3xl`}>
          Cơ sở chưa có tài khoản nhận tiền. Làm lần lượt ba bước bên dưới để tạo QR SePay cho khách
          đặt sân. Đây là tài khoản của cơ sở; khách đặt sân không cần khai tài khoản ngân hàng.
        </p>
      )}
      {(error || resource.error) && (
        <p role="alert" className={`${redNotice} max-w-3xl`}>
          {error || resource.error}
          {resource.error && (
            <button type="button" className="ml-2 underline" onClick={resource.reload}>
              Tải lại kênh nhận tiền
            </button>
          )}
        </p>
      )}
      {message && (
        <p role="status" className={`${emeraldNotice} max-w-3xl`}>
          {message}
        </p>
      )}

      <div className={stepCard}>
        <StepTitle
          step={1}
          title="Liên kết ngân hàng trên SePay"
          description="Đăng nhập SePay và liên kết tài khoản ngân hàng thật của cơ sở. SportHub không thay bạn làm được bước này."
        />
        <a
          href="https://my.sepay.vn"
          target="_blank"
          rel="noreferrer"
          className={`${outlinePill} w-fit`}
        >
          Mở SePay <ExternalLink className="size-4" />
        </a>
      </div>

      <div className={stepCard}>
        <StepTitle
          step={2}
          title="Nhập tài khoản nhận tiền"
          description="Nhập đúng tài khoản đã liên kết ở bước 1."
        />
        {configured && !editing && channel && (
          <>
            <dl className="grid gap-3 text-sm sm:grid-cols-[10rem_1fr]">
              <dt className="text-slate-500 dark:text-slate-400">Ngân hàng</dt>
              <dd className="font-semibold text-slate-900 dark:text-white">
                <BankName bin={channel.bankBin} />
              </dd>
              <dt className="text-slate-500 dark:text-slate-400">Số tài khoản</dt>
              <dd className="font-mono font-semibold text-slate-900 dark:text-white">
                {channel.maskedAccountNumber}
              </dd>
              <dt className="text-slate-500 dark:text-slate-400">Tên người nhận</dt>
              <dd className="font-semibold text-slate-900 dark:text-white">
                {channel.accountName}
              </dd>
            </dl>
            <div className="space-y-2">
              <button
                type="button"
                className={outlinePill}
                onClick={() => {
                  setEditing(true)
                  setMessage('')
                }}
              >
                <Pencil className="size-4" /> Đổi tài khoản
              </button>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Dùng nút này khi đổi tài khoản hoặc cần khóa API mới. Bạn sẽ phải nhập lại số tài
                khoản và cập nhật khóa trên SePay.
              </p>
            </div>
          </>
        )}
        {channel && channel.provider !== 'SEPAY' && (
          <p className={amberNotice}>
            Kênh cũ {channel.maskedAccountNumber} · {channel.accountName} cần thay bằng SePay.
          </p>
        )}
        {showForm && (
          <form ref={formRef} onSubmit={save} autoComplete="off" className="space-y-4">
            <BankSelect
              key={channel?.version ?? 0}
              defaultValue={channel?.bankBin}
              disabled={busy || resource.loading || Boolean(resource.error)}
            />
            <label className={fieldLabel}>
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
            <label className={fieldLabel}>
              Tên người nhận
              <Input
                key={channel?.accountName ?? ''}
                name="accountName"
                defaultValue={channel?.accountName}
                required
                maxLength={100}
                disabled={busy}
              />
            </label>
            <label className={fieldLabel}>
              Khóa API xác thực webhook
              <Input
                key={generated}
                name="webhookApiKey"
                type="password"
                autoComplete="new-password"
                defaultValue={generated || undefined}
                required
                minLength={32}
                maxLength={200}
                pattern="[A-Za-z0-9_.:\-]{32,200}"
                disabled={busy}
              />
            </label>
            <div className="flex flex-wrap items-start gap-2.5">
              <button
                type="button"
                className={outlinePill}
                disabled={busy}
                onClick={() => setGenerated(generateWebhookKey())}
              >
                <KeyRound className="size-4" /> Tạo khóa tự động
              </button>
              <CopyButton
                label="Sao chép khóa"
                emptyMessage="Hãy tạo hoặc nhập khóa trước."
                value={() =>
                  String(new FormData(formRef.current ?? undefined).get('webhookApiKey') ?? '')
                }
              />
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              Khóa được mã hóa ở máy chủ và không hiển thị lại sau khi rời trang. Hãy sao chép để
              dán vào SePay ở bước 3.
            </p>
            <div className="flex flex-wrap gap-2.5">
              <Button
                type="submit"
                className={primaryPill}
                disabled={busy || resource.loading || Boolean(resource.error)}
              >
                {busy ? 'Đang lưu…' : 'Lưu tài khoản'}
              </Button>
              {editing && (
                <button
                  type="button"
                  className={outlinePill}
                  disabled={busy}
                  onClick={() => {
                    setEditing(false)
                    setGenerated('')
                    setError('')
                  }}
                >
                  Hủy
                </button>
              )}
            </div>
          </form>
        )}
      </div>

      <div className={stepCard}>
        <StepTitle
          step={3}
          title="Tạo webhook trên SePay"
          description="SePay dùng webhook để báo tiền vào, nhờ đó lượt đặt được xác nhận tự động."
        />
        {!configured || !channel ? (
          <p className="text-sm text-slate-600 dark:text-slate-400">
            Địa chỉ webhook sẽ hiện ở đây sau khi lưu bước 2.
          </p>
        ) : (
          <>
            <div className="space-y-2">
              <p className="text-sm font-medium text-slate-700 dark:text-slate-300">
                Địa chỉ webhook
              </p>
              <p className="break-all rounded-2xl bg-slate-100 p-3 font-mono text-xs text-slate-800 dark:bg-slate-800 dark:text-slate-200">
                {channel.webhookUrl}
              </p>
              <div className="flex flex-wrap items-start gap-2.5">
                <CopyButton label="Sao chép địa chỉ" value={() => channel.webhookUrl} />
                {savedKey && <CopyButton label="Sao chép khóa vừa lưu" value={() => savedKey} />}
              </div>
            </div>
            <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700 dark:text-slate-300">
              <li>Sự kiện: Có tiền vào, đúng tài khoản ngân hàng ở bước 2.</li>
              <li>Xác thực: API Key, dán khóa của bước 2. Định dạng: JSON.</li>
              <li>Nếu dùng bộ lọc mã thanh toán, đặt tiền tố SC.</li>
            </ul>
            {channel.lastWebhookAt ? (
              <p role="status" className={emeraldNotice}>
                Đã nhận webhook · Lần gần nhất{' '}
                {new Date(channel.lastWebhookAt).toLocaleString('vi-VN')}
              </p>
            ) : (
              <p role="status" className={amberNotice}>
                Đã lưu, chưa nhận webhook nào bằng khóa hiện tại. Trạng thái sẽ đổi sau khi SePay
                báo một giao dịch thật về tài khoản này, ví dụ một khoản chuyển nhỏ.
              </p>
            )}
            <button
              type="button"
              className={outlinePill}
              disabled={resource.loading}
              onClick={resource.reload}
            >
              <RefreshCw className="size-4" /> Kiểm tra lại
            </button>
          </>
        )}
      </div>
    </section>
  )
}
