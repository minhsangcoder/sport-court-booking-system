'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { usePlayWorkspace } from '@/features/groups/use-play-workspace'
import { businessApi } from '@/lib/business-api'
import Link from 'next/link'
export function GroupReuseForm({
  model,
}: {
  model: Pick<
    ReturnType<typeof usePlayWorkspace>,
    'setReuseKey' | 'a' | 'p' | 'reuseKey' | 'router'
  >
}) {
  const { setReuseKey, a, p, reuseKey, router } = model
  return (
    <form
      className="space-y-4 rounded-2xl border border-slate-200/90 p-4"
      onChange={() => setReuseKey(crypto.randomUUID())}
      onSubmit={(e) => {
        e.preventDefault()
        const f = new FormData(e.currentTarget)
        void a.run(
          () =>
            businessApi.reuse(
              p.id,
              String(f.get('holdId')),
              String(f.get('name')),
              Number(f.get('maxMembers')),
              reuseKey,
            ),
          (result) => router.push(`/customer/groups/${result.bookingGroup.id}`),
        )
      }}
    >
      <h3 className="font-bold">Dùng lại nhóm cho một lượt đặt mới</h3>
      <p className="text-sm text-slate-500">
        Chọn sân/khung giờ và giữ chỗ trước. Chỉ người tổ chức tham gia lúc tạo; mời lại từng người
        để họ xác nhận, không thu tiền tự động.
      </p>
      <Link className="font-semibold text-emerald-700 hover:underline" href="/search">
        Chọn lịch sân mới
      </Link>
      <label className="grid gap-2 text-sm">
        Mã giữ chỗ mới
        <Input name="holdId" required pattern="[0-9a-fA-F-]{36}" />
      </label>
      <label className="grid gap-2 text-sm">
        Tên cuộc chơi
        <Input name="name" defaultValue="Cuộc chơi tiếp theo" maxLength={180} />
      </label>
      <label className="grid gap-2 text-sm">
        Sĩ số tối đa
        <Input name="maxMembers" type="number" defaultValue={10} min={1} max={50} required />
      </label>
      <Button type="submit" disabled={a.busy}>
        Tạo lượt đặt mới từ nhóm
      </Button>
    </form>
  )
}
