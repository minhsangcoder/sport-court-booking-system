'use client'
import { useApi } from '@/lib/use-api'
import { Button } from '@/components/ui/button'
import { outlinePill, primaryPill } from './transfer-styles'
import { money } from '@/features/schedule/types'
import { PrivateImage } from './private-image'
import { usePeerCommand } from './use-peer-command'
import type { PeerAttempt } from './peer-types'

type Instruction = {
  amount: string
  description: string
  bankName: string
  maskedAccountNumber: string
  accountName: string
  expiresAt: string
}
export function PeerPayment({
  attempt: a,
  userId,
  reload,
  remaining,
}: {
  attempt: PeerAttempt
  userId: string
  reload: () => void
  remaining: number
}) {
  const buyer = userId === a.buyerId
  const instruction = useApi<Instruction>(
    a.phase === 'WAITING_PAYMENT' && remaining > 0
      ? `/payments/peer-acquisitions/${a.id}/instruction`
      : null,
  )
  const command = usePeerCommand()
  const input = { version: a.version, termsHash: a.termsHash }
  const run = (action: string) =>
    void command.run(`/transfers/acquisitions/${a.id}/${action}`, input, reload)
  return (
    <section className="relative isolate overflow-hidden rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 space-y-4 p-5 sm:p-7">
      <h2 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
        Thanh toán trực tiếp vào tài khoản người bán
      </h2>
      {a.phase === 'WAITING_PAYMENT' && remaining > 0 && (
        <>
          {instruction.data && (
            <>
              <p>
                {instruction.data.bankName} · {instruction.data.maskedAccountNumber} ·{' '}
                {instruction.data.accountName} (tên tự khai)
              </p>
              <p className="font-bold">
                {money(Number(instruction.data.amount), 'VND')} · Nội dung:{' '}
                {instruction.data.description}
              </p>
              <div className="mx-auto max-w-72 rounded-2xl bg-white p-3 shadow-lg ring-4 ring-emerald-100">
                <PrivateImage
                  path={`/payments/peer-acquisitions/${a.id}/qr`}
                  alt="QR SePay chuyển tiền đúng giá cho người bán"
                />
              </div>
            </>
          )}
          {(instruction.loading || instruction.error) && (
            <p role={instruction.error ? 'alert' : 'status'}>
              {instruction.error || 'Đang lấy hướng dẫn chuyển tiền…'}{' '}
              <button type="button" onClick={instruction.reload} className="underline">
                Thử lại
              </button>
            </p>
          )}
          <p className="text-sm text-amber-900">
            Chỉ chuyển khi phiên còn hạn. QR đã lưu ngoài SportHub không thể thu hồi tại ngân hàng;
            không dùng lại mã cũ sau khi hết hạn.
          </p>
          {buyer && (
            <div className="flex flex-wrap gap-2.5">
              <Button
                className={primaryPill}
                disabled={command.busy || command.blocked}
                onClick={() => {
                  if (window.confirm('Bạn đã chuyển đủ tiền cho người bán?')) run('declare-payment')
                }}
              >
                Tôi đã chuyển tiền
              </Button>
              <Button
                variant="outline"
                className={outlinePill}
                disabled={command.busy || command.blocked}
                onClick={() => {
                  if (
                    window.confirm(
                      'Chỉ hủy nếu chưa chuyển tiền. Bạn muốn giải phóng lượt giữ này?',
                    )
                  )
                    run('release')
                }}
              >
                Hủy lượt giữ chưa thanh toán
              </Button>
            </div>
          )}
        </>
      )}
      {a.phase === 'PAYMENT_DECLARED' &&
        (buyer ? (
          <p>
            Đang chờ người bán kiểm tra tiền. Sân chỉ thuộc về bạn khi người bán bấm đã nhận; nếu người
            bán báo chưa nhận hoặc chưa xác nhận trước giờ chơi, sân vẫn thuộc người bán và hai bên tự
            giải quyết với nhau qua chủ sân.
          </p>
        ) : (
          <div className="space-y-3">
            <p>
              Người mua báo đã chuyển tiền. Kiểm tra đủ {money(Number(a.amount), a.currency)} trong tài
              khoản của bạn trước khi xác nhận.
            </p>
            <div className="flex flex-wrap gap-2.5">
              <Button
                className={primaryPill}
                disabled={command.busy || command.blocked}
                onClick={() => {
                  if (
                    window.confirm(
                      'Bạn đã nhận đủ tiền? Xác nhận sẽ bàn giao quyền sân và đổi mã vào sân cho người mua.',
                    )
                  )
                    run('confirm-receipt')
                }}
              >
                Đã nhận đủ tiền, bàn giao sân
              </Button>
              <Button
                variant="outline"
                className={outlinePill}
                disabled={command.busy || command.blocked}
                onClick={() => {
                  if (
                    window.confirm(
                      'Báo chưa nhận tiền sẽ kết thúc phiên này; sân vẫn thuộc bạn và tin được mở lại. Tiếp tục?',
                    )
                  )
                    run('deny-receipt')
                }}
              >
                Chưa nhận được tiền
              </Button>
            </div>
          </div>
        ))}
      {a.phase === 'DISPUTED' && (
        <p className="rounded-2xl border border-amber-200/80 bg-amber-50/70 p-3.5">
          Phiên này được mở theo quy trình cũ. Sân sẽ thuộc người bán khi đến giờ chơi; hai bên tự giải
          quyết với nhau qua chủ sân.
        </p>
      )}
      {command.error && (
        <p role="alert" className="text-red-700">
          {command.error}
        </p>
      )}
    </section>
  )
}
