'use client'
import { AlertCircle, CheckCircle2, ExternalLink, Link2 } from 'lucide-react'
import type { FacilityLocation, LocationPoint } from './location-types'
import { useLocationUrl } from './use-location-url'
export function LocationUrlInput({
  value,
  disabled,
  onApply,
}: {
  value: FacilityLocation
  disabled: boolean
  onApply: (point: LocationPoint) => void
}) {
  const { mapUrlInput, setMapUrlInput, urlResolving, urlMessage, setUrlMessage, handleApplyUrl } =
    useLocationUrl(disabled, onApply)
  return (
    <div className="rounded-2xl border border-emerald-200 bg-emerald-50/60 p-3.5 space-y-2.5">
      <div className="flex flex-wrap items-center justify-between gap-1.5">
        <span className="text-xs font-bold text-emerald-950 flex items-center gap-1.5">
          <Link2 className="h-4 w-4 text-emerald-700" />
          Dán đường dẫn Google Maps hoặc tọa độ
        </span>
        {value && (
          <a
            href={`https://www.google.com/maps/search/?api=1&query=${value.latitude},${value.longitude}`}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-800 hover:text-emerald-950 hover:underline"
            title="Mở Google Maps kiểm tra vị trí này"
          >
            <span>Mở Google Maps kiểm tra</span>
            <ExternalLink className="h-3 w-3" />
          </a>
        )}
      </div>

      <p className="text-[11px] text-slate-600 leading-relaxed">
        Mở Google Maps trên điện thoại hoặc máy tính → Tìm cơ sở của bạn → Bấm nút{' '}
        <strong>&quot;Chia sẻ&quot;</strong> rồi sao chép đường dẫn dán vào đây (hoặc dán đường dẫn
        trên thanh địa chỉ trình duyệt).
      </p>

      <div className="flex flex-col sm:flex-row gap-2">
        <input
          type="text"
          value={mapUrlInput}
          onChange={(e) => {
            setMapUrlInput(e.target.value)
            if (urlMessage) setUrlMessage(null)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              void handleApplyUrl()
            }
          }}
          disabled={disabled || urlResolving}
          placeholder="VD: https://maps.app.goo.gl/... hoặc https://www.google.com/maps/@... hoặc 10.7768, 106.7008"
          className="h-9 flex-1 rounded-lg border border-slate-300 bg-white px-3 text-xs text-slate-800 placeholder:text-slate-400 focus:border-emerald-600 focus:outline-hidden"
        />
        <button
          type="button"
          onClick={() => void handleApplyUrl()}
          disabled={disabled || urlResolving || !mapUrlInput.trim()}
          className="inline-flex h-9 items-center justify-center rounded-full bg-emerald-600 px-4 text-xs font-bold text-white shadow-xs hover:bg-emerald-600 disabled:opacity-50 transition-colors shrink-0"
        >
          {urlResolving ? 'Đang đọc đường dẫn…' : 'Xác định vị trí'}
        </button>
      </div>

      {urlMessage && (
        <div
          className={`flex items-start gap-1.5 text-xs ${
            urlMessage.type === 'success' ? 'font-medium text-emerald-800' : 'text-red-600'
          }`}
        >
          {urlMessage.type === 'success' ? (
            <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-600 mt-0.5" />
          ) : (
            <AlertCircle className="h-4 w-4 shrink-0 text-red-500 mt-0.5" />
          )}
          <span>{urlMessage.text}</span>
        </div>
      )}
    </div>
  )
}
