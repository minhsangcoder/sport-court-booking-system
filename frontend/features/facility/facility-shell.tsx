'use client'
import { RequireAuth } from '@/features/auth/require-auth'
import { useApi } from '@/lib/use-api'
import { PageBanner, bannerGhost } from '@/components/page-banner'
import { Menu, RefreshCw, X } from 'lucide-react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useState } from 'react'
import { facilityNavigation } from './facility-navigation'
import { FacilitySidebar } from './facility-sidebar'
import { FacilityContext } from './facility-store'
import { displayLabel } from '@/lib/display-labels'
import type { Facility, FacilityProfile } from './types'
export function FacilityShell({
  id,
  children,
  applicationId,
}: {
  id: string
  children: React.ReactNode
  applicationId?: string
}) {
  return (
    <RequireAuth roles={applicationId ? ['CUSTOMER', 'OWNER'] : ['OWNER']}>
      <LoadedShell id={id} applicationId={applicationId}>
        {children}
      </LoadedShell>
    </RequireAuth>
  )
}
function LoadedShell({
  id,
  children,
  applicationId,
}: {
  id: string
  children: React.ReactNode
  applicationId?: string
}) {
  const { data, error, loading, reload } = useApi<FacilityProfile>(`/owner/facilities/${id}`)
  const ownerFacilities = useApi<Facility[]>('/owner/facilities')
  const path = usePathname()

  const [sidebarOpen, setSidebarOpen] = useState(false)
  if (loading && !data) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-slate-500">
          <RefreshCw className="size-8 animate-spin text-emerald-600" />
          <p className="text-sm font-semibold">Đang tải không gian cơ sở…</p>
        </div>
      </div>
    )
  }

  if (error || !data) {
    return (
      <div className="mx-auto max-w-xl p-8">
        <div
          role="alert"
          className="rounded-2xl border border-red-200 bg-red-50 p-6 text-sm text-red-700"
        >
          <p className="font-bold">Không thể tải thông tin cơ sở</p>
          <p className="mt-1">{error || 'Cơ sở không tồn tại hoặc bạn không có quyền truy cập.'}</p>
          <div className="mt-4 flex gap-3">
            <button
              onClick={reload}
              className="rounded-lg bg-red-600 px-4 py-2 font-bold text-white hover:bg-red-500"
            >
              Thử lại
            </button>
            <Link
              href="/owner/facilities"
              className="rounded-lg border border-red-300 bg-white px-4 py-2 font-bold text-red-700"
            >
              Về danh sách cơ sở
            </Link>
          </div>
        </div>
      </div>
    )
  }

  const base = applicationId
    ? `/owner/application/${applicationId}/facility`
    : `/owner/facilities/${id}`
  const editable = !['PENDING_APPROVAL', 'REJECTED'].includes(data.status)

  const navGroups = facilityNavigation(applicationId)
  // Find active label for breadcrumb
  let activeLabel = 'Tổng quan cơ sở'
  for (const g of navGroups) {
    for (const item of g.items) {
      if (path === base + item.suffix) {
        activeLabel = item.label
        break
      }
    }
  }
  if (path.endsWith('/maintenance')) activeLabel = 'Bảo trì sân'

  return (
    <FacilityContext.Provider
      value={{ facility: data, reload, basePath: base, editable, applicationId }}
    >
      <PageBanner
        icon="shuttle"
        eyebrow={
          <span className="flex flex-wrap items-center gap-1.5">
            <Link
              href={applicationId ? `/owner/application/${applicationId}` : '/owner/facilities'}
              className="transition hover:text-emerald-200"
            >
              {applicationId ? 'Hồ sơ chủ cơ sở' : 'Cơ sở của tôi'}
            </Link>
            <span aria-hidden>/</span>
            <span>{activeLabel}</span>
          </span>
        }
        title={data.name}
        description={[data.addressLine, data.ward, data.province].filter(Boolean).join(', ')}
        actions={
          <>
            <button
              type="button"
              onClick={() => setSidebarOpen((prev) => !prev)}
              className={`${bannerGhost} lg:hidden`}
              aria-label="Mở hoặc thu gọn thanh bên"
            >
              {sidebarOpen ? <X className="size-4" /> : <Menu className="size-4" />}
              Menu
            </button>
            <span className="inline-flex h-10 items-center gap-2 rounded-full border border-white/25 bg-white/10 px-5 text-sm font-bold text-emerald-200 backdrop-blur-md">
              <span
                className={`size-2 rounded-full ${data.status === 'ACTIVE' ? 'animate-pulse bg-emerald-400' : 'bg-amber-400'}`}
              />
              {displayLabel(data.status)}
            </span>
            <button
              type="button"
              onClick={reload}
              title="Tải lại dữ liệu cơ sở"
              aria-label="Tải lại dữ liệu cơ sở"
              className="inline-flex size-10 items-center justify-center rounded-full border border-white/30 bg-white/10 text-white backdrop-blur-md transition hover:bg-white/20"
            >
              <RefreshCw className="size-4" />
            </button>
          </>
        }
      />
      <div className="owner-workspace mx-auto flex max-w-7xl items-start gap-6 px-4 py-7 sm:px-6 sm:py-9">
        {/* DESKTOP & MOBILE SIDEBAR */}
        <FacilitySidebar
          sidebarOpen={sidebarOpen}
          setSidebarOpen={setSidebarOpen}
          facilities={ownerFacilities.data}
        />

        {/* Backdrop for Mobile Sidebar */}
        {sidebarOpen && (
          <div
            onClick={() => setSidebarOpen(false)}
            className="fixed inset-0 z-20 bg-slate-950/50 backdrop-blur-sm lg:hidden"
          />
        )}

        {/* MAIN WORKSPACE CONTENT */}
        <div className="flex flex-1 flex-col min-w-0">
          {/* Notice for Readonly Mode when Pending Approval */}
          {applicationId && !editable && (
            <div className="mb-6 rounded-2xl border border-amber-200/80 bg-amber-50/70 p-4 text-sm text-amber-950/85 dark:border-amber-500/20 dark:bg-amber-500/5 dark:text-amber-100/85">
              Hồ sơ đã gửi thẩm định được giữ nguyên để quản trị viên kiểm tra. Bạn sẽ nhận được
              thông báo khi được duyệt hoặc cần bổ sung.
            </div>
          )}

          {/* Children View Container */}
          <main className="min-w-0 flex-1">
            {applicationId ? (
              <fieldset disabled={!editable} className="min-w-0">
                {children}
              </fieldset>
            ) : (
              children
            )}
          </main>
        </div>
      </div>
    </FacilityContext.Provider>
  )
}
