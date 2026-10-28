'use client'
import { displayLabel } from '@/lib/display-labels'
import { Building2, ChevronDown, ChevronRight, ExternalLink, PlusCircle } from 'lucide-react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useEffect, useRef, useState } from 'react'
import { facilityNavigation } from './facility-navigation'
import { useFacility } from './facility-store'
import type { Facility } from './types'
export function FacilitySidebar({
  sidebarOpen,
  setSidebarOpen,
  facilities,
}: {
  sidebarOpen: boolean
  setSidebarOpen: React.Dispatch<React.SetStateAction<boolean>>
  facilities: Facility[] | null
}) {
  const { facility: data, basePath: base, applicationId } = useFacility()
  const path = usePathname()
  const navGroups = facilityNavigation(applicationId)
  const ownerFacilities = { data: facilities }
  const [switcherOpen, setSwitcherOpen] = useState(false)
  const switcherRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (switcherRef.current && !switcherRef.current.contains(event.target as Node)) {
        setSwitcherOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  return (
    <aside
      className={`fixed inset-y-0 left-0 z-30 flex w-64 flex-col border-r border-slate-200/80 bg-white transition-transform duration-200 lg:static lg:translate-x-0 dark:border-slate-800 dark:bg-slate-900 ${
        sidebarOpen ? 'translate-x-0' : '-translate-x-full'
      }`}
    >
      <div className="border-b border-slate-200/80 p-3.5 dark:border-slate-800">
        <div className="relative" ref={switcherRef}>
          <button
            type="button"
            onClick={() => setSwitcherOpen((prev) => !prev)}
            className="flex w-full items-center justify-between rounded-lg border border-slate-200 bg-slate-50 p-2 text-left transition hover:border-slate-300 hover:bg-slate-100/70 dark:border-slate-700 dark:bg-slate-800"
          >
            <div className="flex items-center gap-2.5 min-w-0">
              <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-emerald-700 text-white shadow-2xs">
                <Building2 size={15} strokeWidth={1.75} />
              </div>
              <div className="min-w-0">
                <p className="truncate text-xs font-semibold text-slate-900 dark:text-white">
                  {data.name}
                </p>
                <p className="text-[11px] text-slate-500 truncate">
                  {data.province || 'Cơ sở thể thao'}
                </p>
              </div>
            </div>
            <ChevronDown
              size={14}
              strokeWidth={1.75}
              className={`text-slate-400 shrink-0 transition-transform ${switcherOpen ? 'rotate-180' : ''}`}
            />
          </button>

          {switcherOpen && (
            <div className="absolute top-full left-0 mt-1.5 w-full rounded-xl border border-slate-200 bg-white p-1.5 shadow-lg z-50 dark:border-slate-800 dark:bg-slate-900">
              <p className="px-2.5 py-1 text-[10px] font-semibold uppercase tracking-wider text-slate-400">
                Cơ sở của bạn
              </p>
              <div className="max-h-52 overflow-y-auto space-y-0.5">
                {ownerFacilities.data?.map((f) => (
                  <Link
                    key={f.id}
                    href={`/owner/facilities/${f.id}`}
                    onClick={() => setSwitcherOpen(false)}
                    className={`flex items-center justify-between rounded-md px-2.5 py-1.5 text-xs font-medium transition ${
                      f.id === data.id
                        ? 'bg-emerald-50 text-emerald-800 font-semibold dark:bg-emerald-950 dark:text-emerald-400'
                        : 'hover:bg-slate-100 text-slate-700 dark:hover:bg-slate-800 dark:text-slate-300'
                    }`}
                  >
                    <span className="truncate">{f.name}</span>
                    <span className="text-[10px] uppercase font-semibold text-slate-400 ml-1">
                      {displayLabel(f.status)}
                    </span>
                  </Link>
                ))}
              </div>
              <div className="mt-1.5 border-t border-slate-100 pt-1.5 dark:border-slate-800">
                <Link
                  href="/owner/facilities/new"
                  onClick={() => setSwitcherOpen(false)}
                  className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 dark:text-emerald-400 dark:hover:bg-emerald-950"
                >
                  <PlusCircle size={13} strokeWidth={1.75} />
                  <span>Thêm cơ sở mới</span>
                </Link>
                <Link
                  href="/owner/facilities"
                  onClick={() => setSwitcherOpen(false)}
                  className="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-xs font-medium text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                  <span>Tất cả cơ sở</span>
                </Link>
              </div>
            </div>
          )}
        </div>
      </div>

      <nav className="flex-1 overflow-y-auto p-3 space-y-5">
        {!applicationId && (
          <div>
            <p className="px-2.5 mb-1 text-[10px] font-semibold tracking-wider text-slate-400">
              VẬN HÀNH LƯỢT ĐẶT
            </p>
            {[
              ['/owner/bookings', 'Lịch đặt & Check-in khách'],
              ['/owner/counter-booking', 'Đặt tại quầy cho khách'],
            ].map(([href, label]) => (
              <Link
                key={href}
                href={`${href}?facilityId=${data.id}`}
                onClick={() => setSidebarOpen(false)}
                className="flex rounded-lg px-2.5 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50"
              >
                {label}
              </Link>
            ))}
          </div>
        )}
        {navGroups.map((group) => (
          <div key={group.group}>
            <p className="px-2.5 text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-1">
              {group.group}
            </p>
            <div className="space-y-0.5">
              {group.items.map((item) => {
                const itemHref = base + item.suffix
                const active = path === itemHref
                const Icon = item.icon
                return (
                  <Link
                    key={item.suffix}
                    href={itemHref}
                    onClick={() => setSidebarOpen(false)}
                    className={`flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-xs font-medium transition-colors ${
                      active
                        ? 'bg-emerald-700 text-white font-semibold shadow-2xs'
                        : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white'
                    }`}
                  >
                    <Icon
                      size={14}
                      strokeWidth={1.75}
                      className={active ? 'text-white' : 'text-slate-400'}
                    />
                    <span>{item.label}</span>
                  </Link>
                )
              })}
            </div>
          </div>
        ))}
      </nav>

      <div className="border-t border-slate-200/80 p-3 dark:border-slate-800">
        <Link
          href={`/facilities/${data.id}`}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center justify-between rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
        >
          <div className="flex items-center gap-2">
            <ExternalLink size={13} strokeWidth={1.75} className="text-emerald-700" />
            <span>Xem trang công khai</span>
          </div>
          <ChevronRight size={13} strokeWidth={1.75} className="text-slate-400" />
        </Link>
      </div>
    </aside>
  )
}
