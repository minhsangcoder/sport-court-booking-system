'use client'

import { PageBanner, bannerGhost, bannerPrimary } from '@/components/page-banner'
import { RequireAuth } from '@/features/auth/require-auth'
import { money } from '@/features/schedule/types'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import { Calendar, Check, KeyRound, Plus, RotateCcw, Trash2, Users } from 'lucide-react'
import Link from 'next/link'
import React, { useState } from 'react'
import { DeleteGroupDialog } from './delete-group-dialog'
import { useHiddenGroups } from './hidden-groups'
import { type Group, groupStates } from './types'

type TabType = 'ALL' | 'ACTIVE' | 'FINISHED' | 'HIDDEN'

export function GroupList() {
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded />
    </RequireAuth>
  )
}

function Loaded() {
  const { user } = useAuth()
  const r = useApi<Group[]>('/groups')
  const { hide, unhide, isGroupHidden } = useHiddenGroups(user?.id)

  const [activeTab, setActiveTab] = useState<TabType>('ALL')
  const [groupToDelete, setGroupToDelete] = useState<Group | null>(null)
  const [toastMessage, setToastMessage] = useState<string | null>(null)
  const [lastHiddenId, setLastHiddenId] = useState<string | null>(null)

  function showToast(msg: string) {
    setToastMessage(msg)
    setTimeout(() => setToastMessage(null), 3500)
  }

  const allGroups = r.data || []
  const visibleGroups = allGroups.filter((g) => !isGroupHidden(g.id))
  const hiddenGroups = allGroups.filter((g) => isGroupHidden(g.id))

  const activeGroups = visibleGroups.filter(
    (g) => g.state === 'GROUP_PENDING' || g.state === 'CONFIRMED',
  )
  const finishedGroups = visibleGroups.filter(
    (g) => g.state === 'GROUP_EXPIRED' || g.state === 'CANCELLED',
  )

  const displayedGroups =
    activeTab === 'ALL'
      ? visibleGroups
      : activeTab === 'ACTIVE'
        ? activeGroups
        : activeTab === 'FINISHED'
          ? finishedGroups
          : hiddenGroups

  function handleConfirmDelete() {
    if (!groupToDelete) return
    const id = groupToDelete.id
    const name = groupToDelete.name
    hide(id)
    setLastHiddenId(id)
    setGroupToDelete(null)
    showToast(`Đã xóa nhóm "${name}" khỏi danh sách`)
  }

  function handleUndo() {
    if (lastHiddenId) {
      unhide(lastHiddenId)
      setLastHiddenId(null)
      showToast('Đã khôi phục nhóm thành công')
    }
  }

  function handleRestoreSingle(groupId: string, e: React.MouseEvent) {
    e.preventDefault()
    e.stopPropagation()
    unhide(groupId)
    showToast('Đã khôi phục nhóm vào danh sách hiển thị')
  }

  return (
    <>
      <PageBanner
        eyebrow="Chơi cùng bạn bè"
        title="Nhóm đặt sân"
        description="Quản lý các nhóm bạn tham gia, chia sẻ chi phí và cùng chơi thể thao."
        icon="pickleball"
        actions={
          <>
            <Link href="/customer/groups/join" className={bannerGhost}>
              <KeyRound className="size-4 text-emerald-300" strokeWidth={1.75} />
              <span>Nhập mã mời</span>
            </Link>
            <Link href="/search" className={bannerPrimary}>
              <Plus className="size-4" strokeWidth={2} />
              <span>Tạo nhóm từ lịch sân</span>
            </Link>
          </>
        }
      />
      <div className="mx-auto max-w-5xl px-4 sm:px-6 py-8 sm:py-10">
        {/* Delete confirmation dialog */}
        <DeleteGroupDialog
          isOpen={Boolean(groupToDelete)}
          onClose={() => setGroupToDelete(null)}
          onConfirm={handleConfirmDelete}
          groupName={groupToDelete?.name || ''}
        />

        {/* Loading state */}
        {r.loading && (
          <div className="mt-10 flex flex-col items-center justify-center gap-2 py-12 text-slate-400">
            <div className="h-7 w-7 animate-spin rounded-full border-2 border-slate-200 border-t-emerald-600" />
            <p className="text-sm font-medium">Đang tải danh sách nhóm…</p>
          </div>
        )}

        {/* Error state */}
        {r.error && (
          <div className="mt-6 rounded-2xl border border-red-200 bg-red-50 p-5 text-sm text-red-700">
            <div className="flex items-center justify-between">
              <span>{r.error}</span>
              <button className="font-semibold underline hover:text-red-900" onClick={r.reload}>
                Thử lại
              </button>
            </div>
          </div>
        )}

        {/* Filter Tabs */}
        {!r.loading && allGroups.length > 0 && (
          <div className="mt-5 flex flex-wrap items-center gap-1.5 border-b border-slate-200 pb-3">
            <button
              type="button"
              onClick={() => setActiveTab('ALL')}
              className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${
                activeTab === 'ALL'
                  ? 'bg-slate-900 text-white shadow-2xs'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              Tất cả ({visibleGroups.length})
            </button>

            <button
              type="button"
              onClick={() => setActiveTab('ACTIVE')}
              className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${
                activeTab === 'ACTIVE'
                  ? 'bg-emerald-700 text-white shadow-2xs'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              Đang hoạt động ({activeGroups.length})
            </button>

            <button
              type="button"
              onClick={() => setActiveTab('FINISHED')}
              className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${
                activeTab === 'FINISHED'
                  ? 'bg-slate-700 text-white shadow-2xs'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              Đã kết thúc / Hủy ({finishedGroups.length})
            </button>

            {hiddenGroups.length > 0 && (
              <button
                type="button"
                onClick={() => setActiveTab('HIDDEN')}
                className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${
                  activeTab === 'HIDDEN'
                    ? 'bg-rose-600 text-white shadow-2xs'
                    : 'bg-rose-50 text-rose-700 hover:bg-rose-100'
                }`}
              >
                <Trash2 className="h-3 w-3" />
                <span>Đã xóa / Ẩn ({hiddenGroups.length})</span>
              </button>
            )}
          </div>
        )}

        {/* Empty States */}
        {!r.loading && allGroups.length === 0 && (
          <p className="mt-6 rounded-xl border border-slate-200 bg-white p-5 text-xs text-slate-500 leading-relaxed shadow-2xs">
            Bạn chưa tham gia nhóm nào. Chọn sân và hình thức chia tiền ở bước tóm tắt, hoặc nhập mã
            mời.
          </p>
        )}

        {!r.loading && allGroups.length > 0 && displayedGroups.length === 0 && (
          <div className="mt-8 rounded-2xl border border-dashed border-slate-300 bg-slate-50/50 p-8 text-center text-slate-500">
            {activeTab === 'HIDDEN' ? (
              <p className="text-sm">Chưa có nhóm nào trong danh sách đã xóa / ẩn.</p>
            ) : visibleGroups.length === 0 ? (
              <div>
                <p className="text-sm font-medium text-slate-700">
                  Tất cả nhóm của bạn hiện đang ở mục Đã xóa / Ẩn.
                </p>
                <button
                  type="button"
                  onClick={() => setActiveTab('HIDDEN')}
                  className="mt-2 text-xs font-semibold text-emerald-700 underline"
                >
                  Xem danh sách nhóm đã xóa ({hiddenGroups.length})
                </button>
              </div>
            ) : (
              <p className="text-sm">Không có nhóm nào trong mục này.</p>
            )}
          </div>
        )}

        {/* Group Cards Grid */}
        <div className="mt-6 grid gap-4 sm:grid-cols-2">
          {displayedGroups.map((g) => {
            const isHidden = isGroupHidden(g.id)
            const isExpired = g.state === 'GROUP_EXPIRED'
            const isConfirmed = g.state === 'CONFIRMED'

            return (
              <div
                key={g.id}
                className="group relative flex flex-col justify-between rounded-2xl border border-slate-200 bg-white p-5 sm:p-6 shadow-xs transition hover:border-emerald-500 hover:shadow-md"
              >
                {/* Card top row: Status badge + Delete / Restore action */}
                <div className="flex items-center justify-between gap-3">
                  <span
                    className={`inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold border ${
                      isConfirmed
                        ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                        : isExpired
                          ? 'bg-amber-50 text-amber-700 border-amber-200'
                          : 'bg-slate-50 text-slate-700 border-slate-200'
                    }`}
                  >
                    {groupStates[g.state]}
                  </span>

                  {/* Action button: Delete if visible, Restore if hidden */}
                  {isHidden ? (
                    <button
                      type="button"
                      onClick={(e) => handleRestoreSingle(g.id, e)}
                      className="inline-flex items-center gap-1 rounded-lg border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-xs font-semibold text-emerald-700 hover:bg-emerald-100 transition"
                      title="Khôi phục lại nhóm này"
                    >
                      <RotateCcw className="h-3 w-3" />
                      <span>Khôi phục</span>
                    </button>
                  ) : (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.preventDefault()
                        e.stopPropagation()
                        setGroupToDelete(g)
                      }}
                      className="rounded-lg p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600 transition"
                      title="Xóa nhóm khỏi danh sách cá nhân"
                      aria-label={`Xóa nhóm ${g.name}`}
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  )}
                </div>

                {/* Clickable Card Body */}
                <Link href={`/customer/groups/${g.id}`} className="mt-3 block focus:outline-hidden">
                  <h2 className="text-lg sm:text-xl font-bold text-slate-900 group-hover:text-emerald-700 transition">
                    {g.name}
                  </h2>

                  <p className="mt-2 text-xs sm:text-sm text-slate-500 flex items-center gap-1.5">
                    <Calendar className="h-3.5 w-3.5 text-slate-400 shrink-0" />
                    <span>
                      {new Date(g.booking.startsAt).toLocaleString('vi-VN', {
                        timeZone: g.booking.priceSnapshot.timezone,
                      })}
                    </span>
                  </p>

                  <div className="mt-4 flex items-center justify-between border-t border-slate-100 pt-3 text-xs sm:text-sm text-slate-600 font-medium">
                    <span className="flex items-center gap-1">
                      <Users className="h-3.5 w-3.5 text-slate-400" />
                      {g.members.length} thành viên
                    </span>
                    <span className="font-bold text-slate-900">
                      {money(g.totalPaid)} / {money(g.booking.amount)}
                    </span>
                  </div>
                </Link>
              </div>
            )
          })}
        </div>

        {/* Floating feedback toast */}
        {toastMessage && (
          <div
            role="status"
            aria-live="polite"
            className="fixed bottom-6 left-1/2 z-50 -translate-x-1/2 flex items-center gap-3 rounded-full bg-slate-900 px-5 py-2.5 text-sm font-medium text-white shadow-xl animate-in fade-in slide-in-from-bottom-3 duration-200"
          >
            <Check className="h-4 w-4 text-emerald-400" />
            <span>{toastMessage}</span>
            {lastHiddenId && (
              <button
                type="button"
                onClick={handleUndo}
                className="ml-2 font-bold text-emerald-400 underline hover:text-emerald-300 text-xs"
              >
                Hoàn tác
              </button>
            )}
          </div>
        )}
      </div>
    </>
  )
}
