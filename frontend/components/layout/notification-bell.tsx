'use client'
import { Bell } from 'lucide-react'
import { useRouter } from 'next/navigation'
import { useEffect, useRef, useState } from 'react'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { usePolling } from '@/lib/use-polling'

type Notification = {
  id: string
  subject: string
  body: string
  link: string | null
  createdAt: string
  readAt: string | null
}
type Inbox = { unreadCount: number; items: Notification[] }

export function NotificationBell() {
  const router = useRouter()
  const inbox = useApi<Inbox>('/users/me/notifications')
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  usePolling(() => inbox.reload(), { interval: 30000, scope: 'notifications' })

  useEffect(() => {
    if (!open) return
    function close(event: MouseEvent) {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  async function select(item: Notification) {
    setOpen(false)
    if (!item.readAt) {
      await api(`/users/me/notifications/${item.id}/read`, { method: 'POST' }).catch(() => null)
      inbox.reload()
    }
    if (item.link) router.push(item.link)
  }
  async function readAll() {
    await api('/users/me/notifications/read-all', { method: 'POST' }).catch(() => null)
    inbox.reload()
  }

  const unread = inbox.data?.unreadCount ?? 0
  const items = inbox.data?.items ?? []
  return (
    <div className="relative" ref={root}>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-label={unread ? `Thông báo, ${unread} chưa đọc` : 'Thông báo'}
        className="relative flex size-8 cursor-pointer items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-600 shadow-2xs transition-colors hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
      >
        <Bell size={15} />
        {unread > 0 && (
          <span className="absolute -right-1.5 -top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-600 px-1 text-[10px] font-bold text-white">
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 top-full z-50 mt-2 w-[min(22rem,calc(100vw-2rem))] overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl shadow-slate-900/10 dark:border-slate-700 dark:bg-slate-900">
          <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-3 dark:border-slate-800">
            <p className="text-sm font-bold text-slate-900 dark:text-white">Thông báo</p>
            {unread > 0 && (
              <button
                type="button"
                onClick={readAll}
                className="text-xs font-semibold text-emerald-700 hover:underline dark:text-emerald-400"
              >
                Đánh dấu đã đọc
              </button>
            )}
          </div>
          <div className="max-h-96 overflow-y-auto">
            {items.length === 0 && (
              <p className="px-4 py-8 text-center text-sm text-slate-500">
                {inbox.error || 'Chưa có thông báo.'}
              </p>
            )}
            {items.map((item) => (
              <button
                type="button"
                key={item.id}
                onClick={() => select(item)}
                className={`block w-full border-b border-slate-100 px-4 py-3 text-left transition-colors last:border-0 hover:bg-slate-50 dark:border-slate-800 dark:hover:bg-slate-800 ${
                  item.readAt ? '' : 'bg-emerald-50/60 dark:bg-emerald-500/5'
                }`}
              >
                <span className="block text-xs font-bold text-slate-900 dark:text-white">
                  {item.subject}
                </span>
                <span className="mt-1 block whitespace-pre-line break-words text-xs leading-relaxed text-slate-600 dark:text-slate-300">
                  {item.body}
                </span>
                <span className="mt-1.5 block text-[11px] text-slate-400">
                  {new Date(item.createdAt).toLocaleString('vi-VN')}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
