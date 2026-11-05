'use client'
import { confirmDanger } from '@/components/confirm-dialog'
import { UserAvatar } from '@/components/user-avatar'
import { amberNotice, outlinePill, primaryPill } from '@/features/transfers/transfer-styles'
import { api, type UserProfile } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'
import { Camera, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

const maxBytes = 10 * 1024 * 1024
const maxSide = 512

/** Square, upright JPEG: the browser applies the phone's EXIF rotation, which the server cannot see after re-encoding. */
async function squarePhoto(file: File) {
  const bitmap = await createImageBitmap(file)
  const crop = Math.min(bitmap.width, bitmap.height)
  const side = Math.min(crop, maxSide)
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = side
  const context = canvas.getContext('2d')
  if (!context) throw new Error('canvas')
  context.fillStyle = '#fff'
  context.fillRect(0, 0, side, side)
  context.drawImage(
    bitmap,
    (bitmap.width - crop) / 2,
    (bitmap.height - crop) / 2,
    crop,
    crop,
    0,
    0,
    side,
    side,
  )
  bitmap.close()
  return new Promise<Blob>((resolve, reject) =>
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('blob'))), 'image/jpeg', 0.9),
  )
}

/** Choose, preview, then save: nothing is published until the customer confirms. */
export function AvatarUploader() {
  const { user } = useAuth()
  const input = useRef<HTMLInputElement | null>(null)
  const [draft, setDraft] = useState<{ blob: Blob; preview: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const preview = draft?.preview
  useEffect(() => () => (preview ? URL.revokeObjectURL(preview) : undefined), [preview])

  async function choose(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setError('')
    if (!['image/jpeg', 'image/png'].includes(file.type))
      return setError('Vui lòng chọn ảnh JPG hoặc PNG.')
    if (file.size > maxBytes) return setError('Ảnh phải nhỏ hơn 10 MB.')
    try {
      const blob = await squarePhoto(file)
      setDraft({ blob, preview: URL.createObjectURL(blob) })
    } catch {
      setError('Không đọc được ảnh này. Vui lòng chọn ảnh khác.')
    }
  }

  async function send(request: () => Promise<UserProfile>, fallback: string) {
    setBusy(true)
    setError('')
    try {
      const updated = await request()
      window.dispatchEvent(new CustomEvent('sporthub-session', { detail: updated }))
      setDraft(null)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : fallback)
    } finally {
      setBusy(false)
    }
  }

  function save() {
    if (!draft) return
    const body = new FormData()
    body.append('file', draft.blob, 'avatar.jpg')
    void send(
      () => api<UserProfile>('/users/me/avatar', { method: 'PUT', body }),
      'Chưa lưu được ảnh đại diện.',
    )
  }

  async function remove() {
    if (!(await confirmDanger('Xóa ảnh đại diện hiện tại?'))) return
    void send(
      () => api<UserProfile>('/users/me/avatar', { method: 'DELETE' }),
      'Chưa xóa được ảnh đại diện.',
    )
  }

  return (
    <div className="flex flex-col items-center gap-5 sm:flex-row sm:items-center">
      <UserAvatar
        name={user?.fullName}
        src={preview ?? user?.avatarUrl}
        className="size-28 rounded-full text-4xl ring-4 ring-emerald-500/25 shadow-xl shadow-slate-900/10"
      />
      <div className="min-w-0 flex-1 space-y-3 text-center sm:text-left">
        <div>
          <p className="truncate text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
            {user?.fullName || 'Tài khoản SportHub'}
          </p>
          <p className="truncate text-sm text-slate-600 dark:text-slate-400">
            {user?.email || user?.phone}
          </p>
        </div>
        <input
          ref={input}
          type="file"
          accept="image/jpeg,image/png"
          aria-label="Chọn ảnh đại diện"
          className="sr-only"
          onChange={choose}
        />
        <div className="flex flex-wrap justify-center gap-2.5 sm:justify-start">
          {draft ? (
            <>
              <button type="button" className={primaryPill} disabled={busy} onClick={save}>
                {busy ? 'Đang lưu…' : 'Lưu ảnh'}
              </button>
              <button
                type="button"
                className={outlinePill}
                disabled={busy}
                onClick={() => setDraft(null)}
              >
                Hủy
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className={outlinePill}
                disabled={busy}
                onClick={() => input.current?.click()}
              >
                <Camera className="size-4" /> Tải ảnh mới
              </button>
              {user?.avatarUrl && (
                <button type="button" className={outlinePill} disabled={busy} onClick={remove}>
                  <Trash2 className="size-4" /> Xóa ảnh
                </button>
              )}
            </>
          )}
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400">
          {draft
            ? 'Đây là bản xem trước, ảnh chỉ được đổi khi bạn bấm “Lưu ảnh”.'
            : 'JPG hoặc PNG, tối đa 10 MB. Ảnh được cắt vuông từ giữa và không giữ vị trí chụp.'}
        </p>
        {error && (
          <p role="alert" className={`${amberNotice} text-left`}>
            {error}
          </p>
        )}
      </div>
    </div>
  )
}
