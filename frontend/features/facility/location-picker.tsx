'use client'

import { useEffect, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import styles from '@/features/discovery/search-map.module.css'

export type FacilityLocation = { latitude: number; longitude: number } | null

export function LocationPicker({ value, onChange, disabled = false }: {
  value: FacilityLocation
  onChange: (value: FacilityLocation) => void
  disabled?: boolean
}) {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<L.Map | null>(null)
  const marker = useRef<L.Marker | null>(null)
  const initial = useRef(value)
  const locked = useRef(disabled)
  const [ready, setReady] = useState(false)
  const [tileError, setTileError] = useState(false)

  useEffect(() => { locked.current = disabled }, [disabled])
  useEffect(() => {
    if (!container.current) return
    const element = container.current
    const instance = L.map(element, { scrollWheelZoom: false })
    map.current = instance
    // A viewport only: never turn the default centre into a saved location.
    instance.setView(initial.current ? [initial.current.latitude, initial.current.longitude] : [21.028, 105.78], 14)
    const layer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, referrerPolicy: 'origin',
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    })
    layer.on('tileerror', () => { setTileError(true); setReady(true) })
    layer.on('load', () => setReady(true))
    layer.addTo(instance)
    function select(point: L.LatLng) {
      if (locked.current) return
      const wrapped = point.wrap()
      onChange({ latitude: Number(wrapped.lat.toFixed(7)), longitude: Number(wrapped.lng.toFixed(7)) })
    }
    instance.on('click', event => select(event.latlng))
    function keyboard(event: KeyboardEvent) {
      if (event.key === 'Enter' && event.target === element && !locked.current) {
        event.preventDefault(); select(instance.getCenter())
      }
    }
    element.addEventListener('keydown', keyboard)
    const pin = L.marker([0, 0], {
      draggable: true, keyboard: true, title: 'Vị trí cơ sở đã chọn',
      icon: L.divIcon({ html: '●', className: styles.marker, iconSize: [36, 36], iconAnchor: [18, 36] }),
    })
    pin.on('add', () => pin.getElement()?.setAttribute('aria-label', 'Vị trí cơ sở đã chọn'))
    pin.on('dragend', () => select(pin.getLatLng()))
    marker.current = pin
    const observer = new ResizeObserver(() => instance.invalidateSize({ pan: false }))
    observer.observe(element)
    return () => {
      observer.disconnect(); element.removeEventListener('keydown', keyboard)
      layer.off(); instance.remove(); map.current = null; marker.current = null
    }
  }, [onChange])

  useEffect(() => {
    if (!map.current || !marker.current) return
    if (value) marker.current.setLatLng([value.latitude, value.longitude]).addTo(map.current)
    else marker.current.remove()
    if (disabled) marker.current.dragging?.disable()
    else marker.current.dragging?.enable()
  }, [value, disabled])

  return <section className="min-w-0 space-y-3 md:col-span-2" aria-label="Vị trí cơ sở">
    <h2 className="text-lg font-semibold">Vị trí cơ sở</h2>
    <p id="location-instructions" className="text-sm text-slate-600">Chạm hoặc bấm trên bản đồ để đặt điểm; kéo điểm để điều chỉnh. Dùng phím mũi tên và +/− để di chuyển bản đồ, Enter để chọn tâm bản đồ. Vị trí chỉ được lưu khi gửi hoặc lưu biểu mẫu.</p>
    <div ref={container} className={styles.canvas} role="region" aria-label="Chọn vị trí cơ sở trên bản đồ" aria-describedby="location-instructions" />
    {disabled && <p className="text-sm text-slate-500">Chọn vị trí đang tạm khóa.</p>}
    {!ready && <p role="status" className="text-sm text-slate-500">Đang tải bản đồ…</p>}
    {tileError && <p role="alert" className="rounded-xl bg-amber-50 p-3 text-sm text-amber-900">Không tải được một phần bản đồ nền. Vị trí đã chọn vẫn được giữ; bạn có thể sửa địa chỉ và lưu thông tin, hoặc tải lại trang để thử bản đồ.</p>}
    <p role="status" className="text-sm text-slate-600">{value ? `Vĩ độ: ${value.latitude.toFixed(7)} · Kinh độ: ${value.longitude.toFixed(7)}` : 'Chưa chọn vị trí. Tâm bản đồ mặc định không được lưu.'}</p>
    <p className="text-xs text-slate-500">Địa chỉ và điểm bản đồ được chỉnh riêng. Hãy kiểm tra cả hai trước khi lưu.</p>
  </section>
}
