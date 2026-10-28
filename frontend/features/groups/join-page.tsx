'use client'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import type { Court, Facility } from '@/features/facility/types'
import { money } from '@/features/schedule/types'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { Calendar, CheckCircle2, ChevronRight, Clock, MapPin, Users } from 'lucide-react'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import React, { useState } from 'react'
import { QrFileUpload } from './qr-file-upload'
import { extractInviteCode } from './qr-parser'
import type { Group } from './types'

export function JoinPage() {
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded />
    </RequireAuth>
  )
}

function Loaded() {
  const router = useRouter()
  const params = useSearchParams()
  const [code, setCode] = useState(params.get('code') || '')
  const [group, setGroup] = useState<Group | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const facility = useApi<Facility>(group ? `/facilities/${group.booking.facilityId}` : null)
  const courts = useApi<Court[]>(group ? `/facilities/${group.booking.facilityId}/courts` : null)

  async function verifyInviteCode(rawCode: string) {
    const cleanCode = extractInviteCode(rawCode)
    if (!cleanCode) {
      setError('Vui lòng nhập mã mời hợp lệ.')
      return
    }

    setBusy(true)
    setError('')
    try {
      const data = await api<Group>(`/groups/invitations?code=${encodeURIComponent(cleanCode)}`)
      setGroup(data)
      setCode(cleanCode)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Mã mời không hợp lệ hoặc đã hết hạn.')
      setGroup(null)
    } finally {
      setBusy(false)
    }
  }

  async function preview(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    await verifyInviteCode(code)
  }

  function handleQrCodeDetected(detectedCode: string) {
    setCode(detectedCode)
    verifyInviteCode(detectedCode)
  }

  async function join() {
    const cleanCode = extractInviteCode(code)
    setBusy(true)
    setError('')
    try {
      const g = await api<Group>('/groups/join', {
        method: 'POST',
        body: JSON.stringify({ code: cleanCode }),
      })
      router.push(`/customer/groups/${g.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể tham gia.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-50/50 pb-20 pt-6">
      <div className="mx-auto max-w-xl px-4 sm:px-6">
        {/* Breadcrumb */}
        <nav
          aria-label="Đường dẫn trang"
          className="flex items-center gap-1.5 text-xs text-slate-500 mb-5"
        >
          <Link href="/" className="hover:text-slate-900 transition">
            Trang chủ
          </Link>
          <ChevronRight className="h-3.5 w-3.5 text-slate-400" />
          <Link href="/customer/groups" className="hover:text-slate-900 transition">
            Nhóm đặt sân
          </Link>
          <ChevronRight className="h-3.5 w-3.5 text-slate-400" />
          <span className="text-slate-800 font-medium">Tham gia nhóm</span>
        </nav>

        {/* Title */}
        <div className="mb-6">
          <h1 className="text-2xl sm:text-3xl font-black tracking-tight text-slate-900">
            Tham gia nhóm đặt sân
          </h1>
          <p className="mt-1.5 text-sm text-slate-500">
            Tải ảnh mã QR hoặc nhập mã mời để cùng tham gia nhóm và chia sẻ tiền sân.
          </p>
        </div>

        {/* 1. QR Code Image Scanner & Camera */}
        <div className="mb-5">
          <QrFileUpload onCodeDetected={handleQrCodeDetected} busy={busy} />
        </div>

        {/* 2. Divider */}
        <div className="relative my-6 text-center">
          <div className="absolute inset-0 flex items-center">
            <div className="w-full border-t border-slate-200" />
          </div>
          <span className="relative bg-slate-50/50 px-3 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Hoặc nhập mã thủ công
          </span>
        </div>

        {/* 3. Manual Input Form (Exact names/labels preserved for tests) */}
        <form
          onSubmit={preview}
          className="rounded-2xl border border-slate-200/90 bg-white p-5 shadow-xs space-y-4"
        >
          <label className="grid gap-2 text-sm font-semibold text-slate-800">
            Mã mời nhóm
            <Input
              value={code}
              onChange={(e) => {
                setCode(e.target.value)
                setGroup(null)
              }}
              placeholder="Dán mã mời hoặc liên kết mời vào đây..."
              required
              maxLength={500}
              disabled={busy}
              className="h-11 font-mono text-xs sm:text-sm bg-slate-50/60 focus:bg-white"
            />
          </label>
          <Button
            type="submit"
            className="w-full h-11 rounded-xl bg-emerald-600 font-semibold text-white shadow-xs transition hover:bg-emerald-700 active:scale-[0.99]"
            disabled={busy || !code.trim()}
          >
            {busy ? 'Đang kiểm tra…' : 'Kiểm tra lời mời'}
          </Button>
        </form>

        {/* Error message */}
        {error && (
          <div
            role="alert"
            className="mt-5 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm font-medium text-rose-800 animate-in fade-in"
          >
            {error}
          </div>
        )}

        {/* 4. Group Details Preview when verified */}
        {group && (
          <section className="mt-6 overflow-hidden rounded-3xl border border-emerald-200/80 bg-white p-6 shadow-md ring-1 ring-emerald-500/10 animate-in fade-in slide-in-from-bottom-2 duration-300">
            <div className="flex items-start justify-between gap-3 border-b border-slate-100 pb-4">
              <div>
                <span className="inline-flex items-center gap-1 rounded-md bg-emerald-50 px-2.5 py-0.5 text-xs font-semibold text-emerald-800 border border-emerald-200/60">
                  <CheckCircle2 className="h-3 w-3 text-emerald-700" />
                  Lời mời hợp lệ
                </span>
                <h2 className="mt-2 text-xl font-bold text-slate-900">{group.name}</h2>
              </div>
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-emerald-100 text-emerald-800">
                <Users className="h-5 w-5" />
              </div>
            </div>

            <div className="mt-4 space-y-3 text-sm">
              <div className="flex items-start gap-2.5 text-slate-700">
                <MapPin className="h-4 w-4 shrink-0 text-emerald-600 mt-0.5" />
                <div>
                  <p className="font-semibold text-slate-900">
                    {facility.data?.name || 'Cơ sở thể thao'} ·{' '}
                    {courts.data?.find((c) => c.id === group.booking.courtId)?.name || 'Sân đặt'}
                  </p>
                  <p className="text-xs text-slate-500 mt-0.5">
                    {facility.data?.addressLine}, {facility.data?.ward}, {facility.data?.province}
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2.5 text-slate-700">
                <Calendar className="h-4 w-4 shrink-0 text-emerald-600" />
                <span>
                  {new Date(group.booking.startsAt).toLocaleString('vi-VN', {
                    timeZone: group.booking.priceSnapshot.timezone,
                    weekday: 'short',
                    day: '2-digit',
                    month: '2-digit',
                    year: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </span>
              </div>

              <div className="flex items-center gap-2.5 text-slate-700">
                <Users className="h-4 w-4 shrink-0 text-emerald-600" />
                <span>
                  Người tổ chức:{' '}
                  <strong className="text-slate-900">
                    {group.members.find((m) => m.userId === group.ownerId)?.displayName ||
                      'Chủ nhóm'}
                  </strong>
                </span>
              </div>

              <div className="rounded-xl bg-slate-50 p-3.5 border border-slate-100">
                <div className="flex items-center justify-between font-medium">
                  <span className="text-slate-600">Quy mô nhóm:</span>
                  <span className="font-bold text-slate-900">
                    {group.members.length}/{group.maxMembers} thành viên
                  </span>
                </div>
                <div className="mt-1.5 flex items-center justify-between font-medium">
                  <span className="text-slate-600">Tổng tiền sân:</span>
                  <span className="font-bold text-emerald-700 text-base">
                    {money(group.booking.amount)}
                  </span>
                </div>
              </div>

              <div className="flex items-start gap-2 text-xs text-slate-500">
                <Clock className="h-3.5 w-3.5 shrink-0 text-amber-600 mt-0.5" />
                <span>
                  Hạn thanh toán: {new Date(group.deadline).toLocaleString('vi-VN')}. Bạn sẽ thanh
                  toán phần chi phí sau khi người tổ chức chốt danh sách.
                </span>
              </div>
            </div>

            <Button
              className="mt-6 w-full h-11 rounded-xl bg-emerald-600 font-semibold text-white shadow-xs transition hover:bg-emerald-700 active:scale-[0.99]"
              onClick={join}
              disabled={busy || group.allocationsLocked || group.members.length >= group.maxMembers}
            >
              {group.allocationsLocked
                ? 'Nhóm đã khóa danh sách'
                : group.members.length >= group.maxMembers
                  ? 'Nhóm đã đủ thành viên'
                  : 'Xác nhận tham gia'}
            </Button>
          </section>
        )}
      </div>
    </div>
  )
}
