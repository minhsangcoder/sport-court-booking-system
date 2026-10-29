export function RefundPolicyNotice() {
  return (
    <aside
      aria-label="Quy định lượt đặt"
      className="space-y-2.5 rounded-xl bg-slate-50 p-4 text-sm text-slate-600"
    >
      <p className="text-amber-900">
        <strong className="font-semibold text-rose-700">Quy định hoàn tiền:</strong> Không áp dụng
        hoàn tiền cho người đặt ban đầu khi tự hủy lượt đặt. Nếu không thể đến sân, quý khách có thể{' '}
        <strong>Đăng chuyển nhượng lượt đặt</strong> để sang nhượng cho người khác.
      </p>
    </aside>
  )
}
