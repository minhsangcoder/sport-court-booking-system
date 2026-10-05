'use client'

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { ArrowRight, MapPin } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import type { DiscoveryResult } from './types'
import styles from './search-map.module.css'

type Point = {
  facility: DiscoveryResult['facility']
  latitude: number
  longitude: number
  courts: DiscoveryResult[]
}

function MapCanvas({ points, selectedId, onSelect }: {
  points: Point[]
  selectedId: string
  onSelect: (id: string) => void
}) {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<L.Map | null>(null)
  const markers = useRef(new Map<string, L.Marker>())
  const [tileError, setTileError] = useState(false)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    if (!container.current) return
    const instance = L.map(container.current, { scrollWheelZoom: false })
    map.current = instance
    const layer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      // Send only the site origin, never search filters or the user's coordinates.
      referrerPolicy: 'origin',
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    })
    layer.on('tileerror', () => { setTileError(true); setReady(true) })
    layer.on('load', () => setReady(true))
    layer.addTo(instance)

    for (const point of points) {
      const label = `${point.facility.name}: ${point.courts.length} sân phù hợp`
      const content = document.createElement('span')
      content.textContent = String(point.courts.length)
      const marker = L.marker([point.latitude, point.longitude], {
        title: label,
        keyboard: true,
        icon: L.divIcon({ html: content, className: styles.marker, iconSize: [42, 42], iconAnchor: [21, 42] }),
      })
      // A marker may be added before the first map view exists.
      marker.on('add', () => marker.getElement()?.setAttribute('aria-label', label))
      const tooltip = document.createElement('span')
      tooltip.textContent = point.facility.name
      marker.bindTooltip(tooltip)
      marker.on('click', () => onSelect(point.facility.id))
      markers.current.set(point.facility.id, marker)
      marker.addTo(instance)
    }
    instance.fitBounds(points.map(point => [point.latitude, point.longitude]), { padding: [32, 32], maxZoom: 14 })
    const observer = new ResizeObserver(() => instance.invalidateSize({ pan: false }))
    observer.observe(container.current)
    const currentMarkers = markers.current
    return () => {
      observer.disconnect()
      layer.off()
      instance.remove()
      currentMarkers.clear()
      map.current = null
    }
  }, [points, onSelect])

  useEffect(() => {
    for (const [id, marker] of markers.current) {
      marker.getElement()?.classList.toggle(styles.selected, id === selectedId)
      marker.setZIndexOffset(id === selectedId ? 1000 : 0)
    }
    const selected = markers.current.get(selectedId)
    if (selected && map.current && !map.current.getBounds().contains(selected.getLatLng())) {
      map.current.panTo(selected.getLatLng(), { animate: false })
    }
  }, [selectedId, points])

  return <div>
    <div ref={container} className={styles.canvas} role="region" aria-label="Bản đồ các cơ sở có sân phù hợp" />
    {!ready && <p role="status" className="mt-2 text-sm text-slate-500">Đang tải bản đồ…</p>}
    {tileError && <p role="alert" className="mt-2 rounded-xl bg-amber-50 p-3 text-sm text-amber-900">Không tải được một phần bản đồ nền. Bạn vẫn có thể chọn cơ sở bên dưới hoặc dùng danh sách sân.</p>}
  </div>
}

export function SearchMap({ results }: { results: DiscoveryResult[] }) {
  const points = useMemo(() => {
    const groups = new Map<string, Point>()
    for (const result of results) {
      const { latitude, longitude } = result.facility
      if (latitude === null || longitude === null || !Number.isFinite(latitude) || !Number.isFinite(longitude)
        || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) continue
      const existing = groups.get(result.facility.id)
      if (existing) existing.courts.push(result)
      else groups.set(result.facility.id, { facility: result.facility, latitude, longitude, courts: [result] })
    }
    return Array.from(groups.values())
  }, [results])
  const [selectedId, setSelectedId] = useState('')
  const [attempt, setAttempt] = useState(0)
  const select = useCallback((id: string) => setSelectedId(id), [])
  const selected = points.find(point => point.facility.id === selectedId) ?? points[0]
  const missing = results.length - points.reduce((count, point) => count + point.courts.length, 0)

  if (!selected) return <p role="status" className="mt-6 rounded-2xl border bg-white p-6 text-slate-600">Các sân phù hợp chưa có tọa độ bản đồ. Chọn “Danh sách” để xem địa chỉ và giờ chơi.</p>

  return <section className="mt-6 rounded-3xl border bg-white p-4 shadow-sm sm:p-6" aria-label="Kết quả trên bản đồ">
    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
      <p className="text-sm text-slate-600">{points.length} cơ sở · Số trên mỗi điểm là số sân phù hợp bộ lọc.</p>
      <Button variant="outline" size="sm" onClick={() => setAttempt(value => value + 1)}>Tải lại bản đồ</Button>
    </div>
    <MapCanvas key={attempt} points={points} selectedId={selected.facility.id} onSelect={select} />
    <p className="mt-3 text-xs text-slate-500">Chọn một điểm hoặc tên cơ sở. Dùng phím mũi tên để di chuyển bản đồ, +/− để phóng to/thu nhỏ.</p>
    {missing > 0 && <p role="status" className="mt-3 text-sm text-amber-800">{missing} sân chưa có tọa độ hợp lệ; vẫn xem được trong “Danh sách”.</p>}
    <div className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
      <div className="grid content-start gap-2" aria-label="Chọn cơ sở trên bản đồ">
        {points.map(point => <button key={point.facility.id} type="button" aria-pressed={selected.facility.id === point.facility.id} onClick={() => select(point.facility.id)}
          className={`rounded-xl border p-4 text-left ${selected.facility.id === point.facility.id ? 'border-emerald-700 bg-emerald-50' : 'hover:bg-slate-50'}`}>
          <span className="flex items-center gap-2 font-semibold"><MapPin size={16} className="shrink-0 text-emerald-700" />{point.facility.name}</span>
          <span className="mt-1 block text-sm text-slate-600">{point.courts.length} sân phù hợp · {point.facility.district}, {point.facility.province}</span>
        </button>)}
      </div>
      <div className="min-w-0 rounded-2xl bg-slate-50 p-5" aria-live="polite">
        <h3 className="text-lg font-bold"><Link href={`/facilities/${selected.facility.id}`} className="hover:underline">{selected.facility.name}</Link></h3>
        <p className="mt-2 text-sm text-slate-600">{selected.facility.addressLine} · {selected.facility.ward}, {selected.facility.district}, {selected.facility.province}</p>
        <div className="mt-4 grid gap-3">
          {selected.courts.map(court => <article key={court.courtId} className="rounded-xl border bg-white p-4">
            <p className="text-xs font-semibold uppercase tracking-wider text-emerald-700">{court.sportName} · {court.date}</p>
            <h4 className="mt-1 font-bold">{court.courtName}</h4>
            <p className="mt-2 font-semibold text-emerald-800">Từ {money(court.fromPrice, court.currency)} <span className="text-xs font-normal text-slate-500">/ slot</span></p>
            <p className="mt-1 text-sm text-slate-600">{court.availableSlots} slot phù hợp{court.distanceKm !== null ? ` · ${court.distanceKm.toFixed(1)} km từ điểm tìm kiếm` : ''}</p>
            <Link href={`/facilities/${selected.facility.id}/courts/${court.courtId}?date=${court.date}`} className="mt-3 flex items-center justify-between text-sm font-semibold text-emerald-700">Chọn giờ chơi<ArrowRight size={16} /></Link>
          </article>)}
        </div>
      </div>
    </div>
  </section>
}
