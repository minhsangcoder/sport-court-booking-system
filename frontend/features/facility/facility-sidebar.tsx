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
      className={`fixed inset-y-0 left-0 z-30 flex w-72 flex-col border-r border-slate-200/80 bg-white transition-transform duration-200 lg:static lg:translate-x-0 dark:border-slate-800 dark:bg-slate-900 ${
        sidebarOpen ? 'translate-x-0' : '-translate-x-full'
      }`}
    >
      {/* Sidebar Top: Facility Switcher */}
      <div className="border-b border-slate-200/80 p-4 dark:border-slate-800">
        <div className="relative" ref={switcherRef}>
          <button
            type="button"
            onClick={() => setSwitcherOpen((prev) => !prev)}
            className="flex w-full items-center justify-between rounded-xl border border-slate-200 bg-slate-50 p-2.5 text-left transition hover:border-emerald-500 hover:bg-emerald-50/50 dark:border-slate-700 dark:bg-slate-800"
          >
            <div className="flex items-center gap-2.5 min-w-0">
              <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-emerald-600 text-white font-black shadow-sm">
                <Building2 size={18} />
              </div>
              <div className="min-w-0">
                <p className="truncate text-xs font-bold text-slate-900 dark:text-white">
                  {data.name}
                </p>
                <p className="text-[11px] text-slate-500 truncate">
                  {data.province || 'Cơ sở thể thao'}
                </p>
              </div>
            </div>
            <ChevronDown
              size={16}
              className={`text-slate-400 shrink-0 transition-transform ${switcherOpen ? 'rotate-180' : ''}`}
            />
          </button>

          {/* Switcher Dropdown */}
          {switcherOpen && (
            <div className="absolute top-full left-0 mt-2 w-full rounded-2xl border border-slate-200 bg-white p-2 shadow-xl z-50 dark:border-slate-800 dark:bg-slate-900 animate-in fade-in zoom-in-95 duration-150">
              <p className="px-3 py-1.5 text-[11px] font-bold uppercase tracking-wider text-slate-400">
                Cơ sở của bạn
              </p>
              <div className="max-h-52 overflow-y-auto space-y-1">
                {ownerFacilities.data?.map((f) => (
                  <Link
                    key={f.id}
                    href={`/owner/facilities/${f.id}`}
                    onClick={() => setSwitcherOpen(false)}
                    className={`flex items-center justify-between rounded-lg px-3 py-2 text-xs font-semibold transition ${
                      f.id === data.id
                        ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-400'
                        : 'hover:bg-slate-100 text-slate-700 dark:hover:bg-slate-800 dark:text-slate-300'
                    }`}
                  >
                    <span className="truncate">{f.name}</span>
                    <span className="text-[10px] uppercase font-bold text-slate-400 ml-1">
                      {displayLabel(f.status)}
                    </span>
                  </Link>
                ))}
              </div>
              <div className="mt-2 border-t border-slate-100 pt-2 dark:border-slate-800">
                <Link
                  href="/owner/facilities/new"
                  onClick={() => setSwitcherOpen(false)}
                  className="flex items-center gap-2 rounded-lg px-3 py-2 text-xs font-bold text-emerald-600 hover:bg-emerald-50 dark:text-emerald-400 dark:hover:bg-emerald-950"
                >
                  <PlusCircle size={14} />
                  <span>Thêm cơ sở mới</span>
                </Link>
                <Link
                  href="/owner/facilities"
                  onClick={() => setSwitcherOpen(false)}
                  className="flex items-center gap-2 rounded-lg px-3 py-1.5 text-xs font-medium text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800"
                >
                  <span>Tất cả cơ sở</span>
                </Link>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Sidebar Nav Links */}
      <nav className="flex-1 overflow-y-auto p-3 space-y-6">
        {!applicationId && (
          <div>
            <p className="px-3 mb-1.5 text-[10px] font-bold tracking-widest text-slate-400">
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
                className="flex rounded-xl px-3 py-2 text-xs font-bold text-emerald-700 hover:bg-emerald-50"
              >
                {label}
              </Link>
            ))}
          </div>
        )}
        {navGroups.map((group) => (
          <div key={group.group}>
            <p className="px-3 text-[10px] font-bold uppercase tracking-widest text-slate-400 dark:text-slate-500 mb-1.5">
              {group.group}
            </p>
            <div className="space-y-1">
              {group.items.map((item) => {
                const itemHref = base + item.suffix
                const active = path === itemHref
                const Icon = item.icon
                return (
                  <Link
                    key={item.suffix}
                    href={itemHref}
                    onClick={() => setSidebarOpen(false)}
                    className={`flex items-center gap-3 rounded-xl px-3 py-2 text-xs font-bold transition ${
                      active
                        ? 'bg-emerald-600 text-white shadow-sm shadow-emerald-600/30'
                        : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white'
                    }`}
                  >
                    <Icon size={16} className={active ? 'text-white' : 'text-slate-400'} />
                    <span>{item.label}</span>
                  </Link>
                )
              })}
            </div>
          </div>
        ))}
      </nav>

      {/* Sidebar Footer Link to Public View */}
      <div className="border-t border-slate-200/80 p-3 dark:border-slate-800">
        <Link
          href={`/facilities/${data.id}`}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center justify-between rounded-xl bg-slate-100 px-3 py-2.5 text-xs font-bold text-slate-700 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-200"
        >
          <div className="flex items-center gap-2">
            <ExternalLink size={14} className="text-emerald-600" />
            <span>Xem trang công khai</span>
          </div>
          <ChevronRight size={14} className="text-slate-400" />
        </Link>
      </div>
    </aside>
  )
}
