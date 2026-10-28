'use client'
import { LocalCourtImage as Image } from '@/components/local-court-image'
import type { DiscoveryResult } from '@/features/discovery/types'
import type { Media } from '@/features/facility/types'
import { money } from '@/features/schedule/types'
import { useApi } from '@/lib/use-api'
import { ArrowRight, ChevronLeft, ChevronRight, Clock, Expand, MapPin, X } from 'lucide-react'
import Link from 'next/link'
import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

const sportFallback = (sport: string) => {
  const s = (sport || '').toLowerCase()
  if (s.includes('pickleball')) return '/images/hero-pickleball.png'
  if (s.includes('tennis')) return '/images/hero-tennis.png'
  if (s.includes('cầu lông') || s.includes('badminton')) return '/images/hero-paddle.png'
  return '/images/court-aerial.jpg'
}
const formatTime = (value: string, zone: string) =>
  new Date(value).toLocaleTimeString('vi-VN', {
    timeZone: zone,
    hour: '2-digit',
    minute: '2-digit',
  })

type Group = {
  facility: DiscoveryResult['facility']
  courts: DiscoveryResult[]
  sport: string
  slots: number
  first: DiscoveryResult
  from: DiscoveryResult
}

function groupByFacility(items: DiscoveryResult[]): Group[] {
  const map = new Map<string, DiscoveryResult[]>()
  for (const it of items) map.set(it.facility.id, [...(map.get(it.facility.id) ?? []), it])
  return [...map.values()].map((courts) => {
    const sorted = [...courts].sort((a, b) => a.firstStartsAt.localeCompare(b.firstStartsAt))
    const sports = [...new Set(courts.map((c) => c.sportName))]
    return {
      facility: courts[0].facility,
      courts,
      sport: sports.join(' · '),
      slots: courts.reduce((n, c) => n + c.availableSlots, 0),
      first: sorted[0],
      from: courts.reduce((a, b) => (b.fromPrice < a.fromPrice ? b : a)),
    }
  })
}

function Lightbox({
  photos,
  start,
  title,
  onClose,
}: {
  photos: string[]
  start: number
  title: string
  onClose: () => void
}) {
  const [i, setI] = useState(start)
  const n = photos.length
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if (e.key === 'ArrowRight') setI((v) => (v + 1) % n)
      if (e.key === 'ArrowLeft') setI((v) => (v - 1 + n) % n)
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [n, onClose])
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Ảnh ${title}`}
      className="fixed inset-0 z-[100] flex flex-col bg-slate-950/95 backdrop-blur-sm"
      onClick={onClose}
    >
      <div className="flex items-center justify-between px-5 py-4 text-white">
        <p className="text-sm font-semibold">
          {title}{' '}
          <span className="ml-2 font-normal text-slate-400">
            {i + 1} / {n}
          </span>
        </p>
        <button
          type="button"
          onClick={onClose}
          aria-label="Đóng"
          className="flex size-10 cursor-pointer items-center justify-center rounded-full bg-white/10 hover:bg-white/20"
        >
          <X size={20} />
        </button>
      </div>
      <div className="relative flex-1" onClick={(e) => e.stopPropagation()}>
        <Image
          key={photos[i]}
          src={photos[i]}
          alt={`${title} — ảnh ${i + 1}`}
          fill
          sizes="100vw"
          className="object-contain"
        />
        {n > 1 &&
          ([-1, 1] as const).map((d) => (
            <button
              key={d}
              type="button"
              aria-label={d < 0 ? 'Ảnh trước' : 'Ảnh sau'}
              onClick={() => setI((v) => (v + d + n) % n)}
              className={`absolute top-1/2 flex size-12 -translate-y-1/2 cursor-pointer items-center justify-center rounded-full bg-white/15 text-white backdrop-blur hover:bg-white/30 ${d < 0 ? 'left-4' : 'right-4'}`}
            >
              {d < 0 ? <ChevronLeft size={24} /> : <ChevronRight size={24} />}
            </button>
          ))}
      </div>
      {n > 1 && (
        <div
          className="flex justify-center gap-2 overflow-x-auto px-4 py-4"
          onClick={(e) => e.stopPropagation()}
        >
          {photos.map((p, k) => (
            <button
              key={p}
              type="button"
              onClick={() => setI(k)}
              aria-label={`Ảnh ${k + 1}`}
              className={`relative h-14 w-20 shrink-0 cursor-pointer overflow-hidden rounded-lg ring-2 transition ${k === i ? 'ring-emerald-400' : 'ring-transparent opacity-60 hover:opacity-100'}`}
            >
              <Image src={p} alt="" fill sizes="80px" className="object-cover" />
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function Slide({
  g,
  offset,
  today,
  onFocus,
}: {
  g: Group
  offset: number
  today: string
  onFocus: () => void
}) {
  const f = g.facility
  const media = useApi<Media[]>(`/facilities/${f.id}/images`)
  const [zoom, setZoom] = useState(false)
  const photos = useMemo(() => {
    const uploaded = (media.data ?? []).map((m) => m.url).filter(Boolean)
    return uploaded.length ? uploaded : [sportFallback(g.courts[0].sportName)]
  }, [media.data, g.courts])
  const active = offset === 0
  const single = g.courts.length === 1
  const href = single
    ? `/facilities/${f.id}/courts/${g.first.courtId}?date=${g.first.date || today}`
    : `/facilities/${f.id}`
  const abs = Math.abs(offset)

  return (
    <div
      className="absolute left-1/2 top-0 h-full w-[88%] max-w-[1040px] transition-all duration-700 ease-[cubic-bezier(0.22,1,0.36,1)] sm:w-[78%] lg:w-[70%]"
      style={{
        transform: `translateX(calc(-50% + ${offset * 62}%)) scale(${active ? 1 : 0.8}) rotateY(${-offset * 14}deg)`,
        opacity: abs > 1 ? 0 : active ? 1 : 0.5,
        zIndex: 20 - abs,
        pointerEvents: abs > 1 ? 'none' : 'auto',
        filter: active ? 'none' : 'saturate(0.7)',
      }}
      aria-hidden={!active}
    >
      <article className="group relative isolate h-full overflow-hidden rounded-[2rem] bg-slate-900 shadow-2xl shadow-slate-950/40">
        <Image
          src={photos[0]}
          alt={`${f.name}`}
          fill
          sizes="(max-width: 1024px) 90vw, 900px"
          className="-z-10 object-cover transition-transform duration-[1400ms] ease-out group-hover:scale-105"
        />
        <div className="absolute inset-0 -z-10 bg-gradient-to-t from-slate-950/90 via-slate-950/20 to-slate-950/30" />

        {!active && (
          <button
            type="button"
            onClick={onFocus}
            tabIndex={-1}
            aria-label={`Xem ${f.name}`}
            className="absolute inset-0 z-10 cursor-pointer"
          />
        )}

        <div className="absolute left-6 right-6 top-6 flex items-start justify-between gap-3">
          <span className="rounded-full bg-white/90 px-4 py-1.5 text-xs font-bold uppercase tracking-wide text-slate-900">
            {g.sport}
          </span>
          {active && (
            <button
              type="button"
              onClick={() => setZoom(true)}
              className="inline-flex cursor-pointer items-center gap-2 rounded-full bg-white/15 px-4 py-2 text-sm font-semibold text-white backdrop-blur-md transition hover:bg-white/30"
            >
              <Expand size={16} />
              Xem ảnh lớn{photos.length > 1 ? ` · ${photos.length}` : ''}
            </button>
          )}
        </div>

        <div className="absolute inset-x-0 bottom-0 p-6 sm:p-9">
          <h3 className="font-display text-4xl font-extrabold uppercase italic leading-[1.02] text-white sm:text-6xl">
            {f.name}
          </h3>
          <div
            className={`grid transition-all duration-500 ${active ? 'grid-rows-[1fr] opacity-100 delay-300' : 'grid-rows-[0fr] opacity-0'}`}
          >
            <div className="overflow-hidden">
              <div className="mt-4 flex flex-wrap items-end justify-between gap-5">
                <div className="space-y-1.5 text-sm text-slate-200">
                  <p className="flex items-center gap-2">
                    <span className="relative flex size-2">
                      <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-300 opacity-75" />
                      <span className="relative inline-flex size-2 rounded-full bg-emerald-300" />
                    </span>
                    {g.slots} khung giờ trống ·{' '}
                    {single ? g.first.courtName : `${g.courts.length} sân`}
                  </p>
                  <p className="flex items-center gap-2">
                    <MapPin size={14} />
                    {f.addressLine}, {f.ward}, {f.province}
                  </p>
                  <p className="flex items-center gap-2">
                    <Clock size={14} />
                    Sớm nhất {formatTime(g.first.firstStartsAt, f.timezone)}
                  </p>
                </div>
                <div className="flex items-center gap-5">
                  <div className="text-right text-white">
                    <span className="block text-xs text-slate-300">Từ</span>
                    <span className="text-3xl font-extrabold tabular-nums">
                      {money(g.from.fromPrice, g.from.currency)}
                    </span>
                    <span className="text-sm text-slate-300"> /giờ</span>
                  </div>
                  <Link
                    href={href}
                    tabIndex={active ? 0 : -1}
                    className="inline-flex h-12 items-center gap-2 rounded-full bg-white px-6 text-sm font-bold text-slate-900 transition hover:bg-emerald-400"
                  >
                    {single ? 'Đặt lịch' : 'Xem cụm sân'}
                    <ArrowRight size={16} />
                  </Link>
                </div>
              </div>
            </div>
          </div>
        </div>
      </article>
      {zoom &&
        createPortal(
          <Lightbox photos={photos} start={0} title={f.name} onClose={() => setZoom(false)} />,
          document.body,
        )}
    </div>
  )
}

export function CourtCarousel({ items, today }: { items: DiscoveryResult[]; today: string }) {
  const sports = useMemo(() => [...new Set(items.map((i) => i.sportName))], [items])
  const [tab, setTab] = useState('')
  const [active, setActive] = useState(0)
  const groups = useMemo(
    () => groupByFacility(tab ? items.filter((i) => i.sportName === tab) : items),
    [items, tab],
  )
  const n = groups.length
  const drag = useRef<number | null>(null)
  const step = (d: number) => setActive((v) => (v + d + n) % n)
  const offsetOf = (i: number) => {
    let d = i - active
    if (d > n / 2) d -= n
    if (d < -n / 2) d += n
    return d
  }

  const tabClass = (on: boolean) =>
    `cursor-pointer whitespace-nowrap rounded-full px-4 py-2 text-sm font-semibold transition-colors ${
      on
        ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
        : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:ring-emerald-400 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700'
    }`
  const pick = (next: string) => {
    setTab(next)
    setActive(0)
  }

  return (
    <div className="mt-6">
      <div className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        <button type="button" className={tabClass(tab === '')} onClick={() => pick('')}>
          Tất cả
        </button>
        {sports.map((s) => (
          <button key={s} type="button" className={tabClass(tab === s)} onClick={() => pick(s)}>
            {s}
          </button>
        ))}
      </div>

      <div
        role="region"
        aria-roledescription="carousel"
        aria-label="Cụm sân còn lịch trống"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'ArrowRight') step(1)
          if (e.key === 'ArrowLeft') step(-1)
        }}
        onPointerDown={(e) => (drag.current = e.clientX)}
        onPointerUp={(e) => {
          if (drag.current !== null && Math.abs(e.clientX - drag.current) > 60)
            step(e.clientX < drag.current ? 1 : -1)
          drag.current = null
        }}
        className="relative mt-8 h-[500px] touch-pan-y select-none outline-none [perspective:1600px] sm:h-[580px] lg:h-[640px]"
      >
        {groups.map((g, i) => (
          <Slide
            key={g.facility.id}
            g={g}
            offset={offsetOf(i)}
            today={today}
            onFocus={() => setActive(i)}
          />
        ))}
        {n > 1 &&
          ([-1, 1] as const).map((d) => (
            <button
              key={d}
              type="button"
              aria-label={d < 0 ? 'Cụm sân trước' : 'Cụm sân sau'}
              onClick={() => step(d)}
              className={`absolute top-1/2 z-30 flex size-14 -translate-y-1/2 cursor-pointer items-center justify-center rounded-full bg-white text-slate-900 shadow-xl ring-1 ring-slate-200 transition hover:scale-110 hover:bg-emerald-500 hover:text-white ${d < 0 ? 'left-1 sm:left-4' : 'right-1 sm:right-4'}`}
            >
              {d < 0 ? <ChevronLeft size={24} /> : <ChevronRight size={24} />}
            </button>
          ))}
      </div>

      <div className="mt-6 flex items-center justify-center gap-2" aria-hidden>
        {groups.map((g, i) => (
          <button
            key={g.facility.id}
            type="button"
            tabIndex={-1}
            onClick={() => setActive(i)}
            className={`h-2 cursor-pointer rounded-full transition-all duration-300 ${i === active ? 'w-8 bg-slate-900 dark:bg-white' : 'w-2 bg-slate-300 hover:bg-slate-400'}`}
          />
        ))}
      </div>
      <div className="mt-6 text-center">
        <Link
          href={`/search?date=${today}`}
          className="inline-flex items-center gap-2 text-sm font-semibold text-emerald-700 hover:underline dark:text-emerald-400"
        >
          Xem tất cả sân <ArrowRight size={15} />
        </Link>
      </div>
    </div>
  )
}
