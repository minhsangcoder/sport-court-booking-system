'use client'
import { CircleAlert, CircleCheck, TriangleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useSyncExternalStore } from 'react'

type Tone = 'default' | 'danger' | 'info'
type Options = {
  title?: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  tone?: Tone
}
type Request = Options & { kind: 'confirm' | 'inform'; resolve: (ok: boolean) => void }

// One dialog at a time; later requests wait in line. Replaces window.confirm / window.alert.
let queue: Request[] = []
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())
const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}
const current = () => queue[0] ?? null

function open(kind: Request['kind'], input: string | Options) {
  const options = typeof input === 'string' ? { message: input } : input
  return new Promise<boolean>((resolve) => {
    queue = [...queue, { ...options, kind, resolve }]
    emit()
  })
}
function close(ok: boolean) {
  const [head, ...rest] = queue
  if (!head) return
  queue = rest
  emit()
  head.resolve(ok)
}

/** Ask the user to confirm an action. Resolves true only when they press the confirm button. */
export const confirmDialog = (input: string | Options) => open('confirm', input)
/** Confirm an irreversible or destructive action (red confirm button). */
export const confirmDanger = (input: string | Options) =>
  open('confirm', { ...(typeof input === 'string' ? { message: input } : input), tone: 'danger' })
/** Tell the user something; resolves when they dismiss it. */
export const informDialog = (input: string | Options) => open('inform', input).then(() => undefined)

const tones: Record<Tone, { icon: React.ReactNode; badge: string; button: string }> = {
  default: {
    icon: <CircleAlert size={22} />,
    badge: 'bg-amber-50 text-amber-600 dark:bg-amber-950 dark:text-amber-300',
    button: 'bg-emerald-600 hover:bg-emerald-700 shadow-emerald-600/20',
  },
  danger: {
    icon: <TriangleAlert size={22} />,
    badge: 'bg-red-50 text-red-600 dark:bg-red-950 dark:text-red-300',
    button: 'bg-red-600 hover:bg-red-700 shadow-red-600/20',
  },
  info: {
    icon: <CircleCheck size={22} />,
    badge: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-300',
    button: 'bg-emerald-600 hover:bg-emerald-700 shadow-emerald-600/20',
  },
}

/** Mounted once in the root layout; renders whichever dialog is first in line. */
export function DialogHost() {
  const request = useSyncExternalStore(subscribe, current, () => null)
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const messageId = useId()
  // The native modal dialog gives the top layer, focus trapping and Escape handling.
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (request && !dialog.open) dialog.showModal()
    if (!request && dialog.open) dialog.close()
  }, [request])
  const tone = tones[request?.tone ?? (request?.kind === 'inform' ? 'info' : 'default')]
  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={messageId}
      onCancel={(e) => {
        e.preventDefault()
        close(false)
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) close(false)
      }}
      className="m-auto w-[calc(100%-2rem)] max-w-md rounded-3xl border border-slate-200/90 bg-white p-0 shadow-2xl shadow-slate-900/20 backdrop:bg-slate-950/50 backdrop:backdrop-blur-sm dark:border-slate-800 dark:bg-slate-900"
    >
      {request && (
        <div className="p-6">
          <div className="flex gap-4">
            <div
              className={`flex size-11 shrink-0 items-center justify-center rounded-2xl ${tone.badge}`}
            >
              {tone.icon}
            </div>
            <div className="min-w-0">
              <h2
                id={titleId}
                className="text-lg font-extrabold tracking-tight text-slate-900 dark:text-white"
              >
                {request.title ?? (request.kind === 'inform' ? 'Thông báo' : 'Xác nhận thao tác')}
              </h2>
              <p
                id={messageId}
                className="mt-1.5 whitespace-pre-line text-sm leading-relaxed text-slate-600 dark:text-slate-300"
              >
                {request.message}
              </p>
            </div>
          </div>
          <div className="mt-6 flex flex-wrap justify-end gap-2.5">
            {request.kind === 'confirm' && (
              <button
                type="button"
                onClick={() => close(false)}
                className="rounded-full px-5 py-2.5 text-sm font-semibold text-slate-700 ring-1 ring-slate-200 transition hover:ring-emerald-400 dark:text-slate-200 dark:ring-slate-700"
              >
                {request.cancelLabel ?? 'Hủy'}
              </button>
            )}
            <button
              type="button"
              autoFocus
              onClick={() => close(true)}
              className={`rounded-full px-5 py-2.5 text-sm font-semibold text-white shadow-lg transition ${tone.button}`}
            >
              {request.confirmLabel ?? (request.kind === 'inform' ? 'Đã hiểu' : 'Xác nhận')}
            </button>
          </div>
        </div>
      )}
    </dialog>
  )
}
