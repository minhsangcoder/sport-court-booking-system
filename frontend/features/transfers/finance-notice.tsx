import { demoVnd } from '@/features/facility/demo-report'
import type { TransferFinanceTerms } from './types'
export function TransferFinanceNotice({ terms }: { terms: TransferFinanceTerms }) {
  if (terms.protocol === 'PEER_RESALE_V1')
    return (
      <section className="my-5 space-y-3 rounded-3xl border border-emerald-200/80 bg-gradient-to-br from-emerald-50 to-teal-50/60 p-5 text-sm leading-relaxed text-emerald-950/85 shadow-sm sm:p-6 dark:border-emerald-500/20 dark:from-emerald-500/10 dark:to-teal-500/5 dark:text-emerald-100/85">
        <h2 className="text-base font-extrabold tracking-tight text-emerald-950 dark:text-emerald-200">
          Chuyển nhượng trực tiếp giữa hai khách
        </h2>
        <p>
          Người mua trả <strong>{demoVnd(terms.amount)}</strong> trực tiếp vào tài khoản người bán
          đã chốt. Không có phí chuyển nhượng hoặc khoản cơ sở trả người bán.
        </p>
        <p>
          Người mua gửi ảnh và mã giao dịch; người bán xác nhận đã nhận đủ tiền thì hệ thống bàn
          giao quyền sân. Ảnh, mã QR và xác nhận hai bên không phải xác minh giao dịch từ ngân hàng.
        </p>
        <p>
          Chủ cơ sở chỉ phân xử quyền dùng sân khi tranh chấp, không hoàn khoản tiền P2P. Sau khi
          nhận quyền, người mua không được chuyển nhượng tiếp.
        </p>
        <p>Lượt đặt nhận qua chuyển nhượng không áp dụng hoàn tiền khi tự hủy.</p>
      </section>
    )
  if (terms.mode !== 'DEMO' && terms.mode !== 'REAL')
    return (
      <p className="my-5 rounded-2xl border border-amber-200/80 bg-amber-50/70 p-4 text-sm text-amber-950/85">
        Đây là điều khoản giao dịch cũ, chỉ phục vụ lịch sử. Tin đăng mới sử dụng chuyển nhượng trực
        tiếp, không có phí cơ sở.
      </p>
    )
  return (
    <section className="my-5 space-y-3 rounded-3xl border border-emerald-200/80 bg-emerald-50/70 p-5 text-sm sm:p-6">
      <h2 className="font-bold">Điều khoản giao dịch cũ · chỉ xem lịch sử</h2>
      <p>
        Bên nhận thanh toán:{' '}
        <strong>
          {terms.facilityName || terms.receiverName || 'Cơ sở của sân được chuyển nhượng'}
        </strong>
        .
      </p>
      <dl className="grid grid-cols-2 gap-3">
        <dt>Người mua thanh toán</dt>
        <dd className="text-right font-semibold">{demoVnd(terms.amount)}</dd>
        <dt>Phí cơ sở ({(terms.feeBps / 100).toLocaleString('vi-VN')}%)</dt>
        <dd className="text-right">{demoVnd(terms.quotedFeeAmount)}</dd>
        <dt>Cơ sở phải trả người bán</dt>
        <dd className="text-right font-semibold">{demoVnd(terms.quotedSellerNetAmount)}</dd>
      </dl>
      <p>
        Người mua nhận quyền sau{' '}
        {terms.mode === 'REAL'
          ? 'khi xác minh đủ tiền vào cơ sở'
          : 'thanh toán mô phỏng thành công'}{' '}
        và bàn giao, sử dụng theo lịch; không phải chờ cơ sở trả người bán.
      </p>
      <p className="text-xs text-slate-600">
        Lượt đặt nhận qua chuyển nhượng không áp dụng hoàn tiền khi tự hủy. Phí thuộc cơ sở;
        SportHub không hưởng phí.{' '}
        {terms.mode === 'DEMO'
          ? 'Không phát sinh giao dịch tiền thật.'
          : 'Cơ sở thực hiện trả/hoàn qua ngân hàng và xác nhận mã giao dịch.'}
      </p>
    </section>
  )
}
