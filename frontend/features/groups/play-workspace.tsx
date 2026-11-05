'use client'
import { confirmDanger } from '@/components/confirm-dialog'
import { Button } from '@/components/ui/button'
import { GroupReuseForm } from '@/features/groups/group-reuse-form'
import { usePlayWorkspace } from '@/features/groups/use-play-workspace'
import { Card, Status } from '@/features/integration/shared'
import { businessApi, type ChatMessage, type PlayGroup } from '@/lib/business-api'
import { displayLabel } from '@/lib/display-labels'
export function PlayWorkspace({
  root: p,
  reload,
  completed,
  terminal,
}: {
  root: PlayGroup
  reload: () => void
  completed: boolean
  terminal: boolean
}) {
  const model = usePlayWorkspace({
    root: p,
    reload,
    completed,
    terminal,
  })
  const {
    r,
    a,
    messages,
    setOlder,
    older,
    text,
    messageKey,
    setText,
    setMessageKey,
    owner,
    router,
  } = model
  return (
    <Card title="Nhóm chơi & trò chuyện">
      <p className="text-sm text-slate-500">
        {displayLabel(p.state)} · {p.members.length} thành viên hiện tại. Thành viên đã rời không
        đọc được lịch sử.
      </p>
      <Status loading={r.loading} error={r.error || a.error} />
      <ol
        className="max-h-96 space-y-3 overflow-y-auto rounded-2xl bg-slate-50 p-4"
        aria-label="Tin nhắn nhóm"
      >
        {messages.map((m) => (
          <li key={m.id}>
            <strong className="text-sm">{m.display_name ?? m.author_name ?? 'Thành viên'}</strong>
            <p className="whitespace-pre-wrap break-words">{m.body}</p>
            <time className="text-xs text-slate-500">
              {new Date(m.created_at).toLocaleString('vi-VN')}
            </time>
          </li>
        ))}
      </ol>
      {messages.length > 0 && (
        <Button
          variant="outline"
          disabled={a.busy}
          onClick={() =>
            a.run(
              () =>
                import('@/lib/api').then(({ api }) =>
                  api<ChatMessage[]>(
                    `/groups/play-groups/${p.id}/messages?size=50&before=${messages[0].id}`,
                  ),
                ),
              (rows) => setOlder([...older, ...rows]),
            )
          }
        >
          Tải tin nhắn trước
        </Button>
      )}
      {p.state === 'DISSOLVED' ? (
        <p className="rounded-2xl bg-amber-50 p-4">
          Nhóm đã giải tán. Lịch sử vẫn đọc được với thành viên hiện tại; gửi tin nhắn đã đóng.
        </p>
      ) : (
        <form
          className="flex flex-wrap gap-3"
          onSubmit={(e) => {
            e.preventDefault()
            void a.run(
              () => businessApi.message(p.id, text, messageKey),
              () => {
                setText('')
                setMessageKey(crypto.randomUUID())
                r.reload()
              },
            )
          }}
        >
          <label className="grid flex-1 gap-2 text-sm">
            Tin nhắn
            <textarea
              value={text}
              onChange={(e) => {
                setText(e.target.value)
                setMessageKey(crypto.randomUUID())
              }}
              required
              maxLength={2000}
              className="rounded-2xl border border-slate-200/90 p-3"
            />
          </label>
          <Button type="submit" className="self-end" disabled={a.busy || !text.trim()}>
            Gửi tin nhắn
          </Button>
        </form>
      )}
      <div className="flex flex-wrap gap-3">
        {owner && completed && p.state === 'OPEN' && (
          <Button
            disabled={a.busy}
            onClick={() => a.run(() => businessApi.playAction(p.id, 'KEEP', p.version), reload)}
          >
            Giữ nhóm cho lần chơi sau
          </Button>
        )}
        {owner && terminal && p.state !== 'DISSOLVED' && (
          <Button
            variant="outline"
            disabled={a.busy}
            onClick={async () => {
              if (
                await confirmDanger(
                  'Giải tán nhóm chơi và đóng gửi tin nhắn? Khoản đóng góp và lượt đặt đã thanh toán được giữ nguyên.',
                )
              )
                void a.run(() => businessApi.playAction(p.id, 'DISSOLVE', p.version), reload)
            }}
          >
            Giải tán nhóm chơi
          </Button>
        )}
        {!owner && completed && (
          <Button
            variant="outline"
            disabled={a.busy}
            onClick={async () => {
              if (
                await confirmDanger(
                  'Rời nhóm chơi sẽ thu hồi đọc/gửi tin nhắn; không sửa khoản đã đóng cho lượt đặt.',
                )
              )
                void a.run(
                  () => businessApi.leavePlay(p.id),
                  () => router.push('/customer/groups'),
                )
            }}
          >
            Rời nhóm chơi
          </Button>
        )}
      </div>
      {owner && p.state === 'KEPT' && <GroupReuseForm model={model} />}
    </Card>
  )
}
