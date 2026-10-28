'use client'

import { Button } from '@/components/ui/button'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useRef, useState } from 'react'
import type { Group, Invite } from './types'

export function GroupPaymentSetup({ group, reload }: { group: Group; reload: () => void }) {
  const accepting = !group.allocationsLocked && group.members.length < group.maxMembers
  const invite = useApi<Invite>(accepting ? `/groups/${group.id}/invite` : null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const flight = useRef(false)

  async function copy() {
    if (!invite.data) return
    try {
      await navigator.clipboard.writeText(invite.data.code)
      setMessage('Đã sao chép mã mời.')
    } catch {
      setMessage('Hãy chọn và sao chép mã mời bên dưới.')
    }
  }

  async function split() {
    if (flight.current) return
    flight.current = true
    setBusy(true)
    setError('')
    try {
      await api(`/groups/${group.id}/allocations`, {
        method: 'PUT',
        body: JSON.stringify({ mode: 'EQUAL', allocations: null }),
      })
      reload()
    } catch (failure) {
      setError(
        failure instanceof Error ? failure.message : 'Không thể chia tiền. Vui lòng thử lại.',
      )
      reload()
    } finally {
      flight.current = false
      setBusy(false)
    }
  }

  return (
    <section
      aria-label="Mời thành viên và chia tiền"
      className="mt-6 space-y-4 rounded-2xl border p-5"
    >
      <h2 className="text-lg font-bold">Mời bạn cùng thanh toán</h2>
      {accepting && (
        <>
          <p className="text-sm text-slate-600">
            Gửi mã này cho bạn bè để nhập tại trang Tham gia nhóm. Danh sách và tiến độ thanh toán
            tự cập nhật mỗi 5 giây; khi đủ người, bạn chốt chia tiền bên dưới.
          </p>
          {invite.loading && !invite.data && <p role="status">Đang tải mã mời…</p>}
          {invite.error && (
            <p role="alert" className="text-sm text-red-700">
              {invite.error}{' '}
              <button className="underline" onClick={invite.reload}>
                Thử lại
              </button>
            </p>
          )}
          {invite.data && (
            <div className="space-y-3">
              <label className="grid gap-2 text-sm font-medium">
                Mã mời nhóm
                <textarea
                  readOnly
                  value={invite.data.code}
                  rows={3}
                  className="w-full rounded-lg border p-3 font-mono text-xs"
                />
              </label>
              <Button variant="outline" onClick={copy}>
                Sao chép mã mời
              </Button>
              <Link
                className="ml-4 text-sm text-emerald-700 underline"
                href={`/customer/groups/${group.id}/invite`}
              >
                Xem QR và liên kết mời
              </Link>
            </div>
          )}
        </>
      )}
      <p className="text-sm text-slate-600">
        Hiện có {group.members.length}/{group.maxMembers} thành viên. Chốt chia tiền sẽ đóng lời
        mời; mỗi người thanh toán phần của mình trong danh sách bên dưới.
      </p>
      <div className="flex flex-wrap items-center gap-3">
        <Button disabled={busy} onClick={split}>
          Chia đều và bắt đầu thanh toán
        </Button>
        <Link
          className="text-sm text-emerald-700 underline"
          href={`/customer/groups/${group.id}/split`}
        >
          Tùy chỉnh phần tiền từng người
        </Link>
      </div>
      {message && (
        <p role="status" className="text-sm">
          {message}
        </p>
      )}
      {error && (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      )}
    </section>
  )
}
