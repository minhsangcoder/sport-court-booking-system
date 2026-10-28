'use client'

import React, { useState, useEffect } from 'react'
import { Compass, MapPin, Navigation, Copy, Check, Share2, ExternalLink } from 'lucide-react'

export interface DirectionsCardProps {
  title?: string
  subtitle?: string
  venueName?: string
  addressLine: string
  ward?: string
  province?: string
  latitude?: number | null
  longitude?: number | null
  className?: string
  showShare?: boolean
}

export function DirectionsCard({
  title = 'Bản Đồ & Điều Hướng Trực Tiếp',
  subtitle = 'Xem vị trí thực tế trên Google Maps và nhận chỉ đường chính xác',
  venueName = 'Aura Court Cầu Giấy',
  addressLine,
  ward,
  province,
  latitude,
  longitude,
  className = '',
  showShare = true,
}: DirectionsCardProps) {
  const [copied, setCopied] = useState(false)
  const [toastMessage, setToastMessage] = useState<string | null>(null)
  const [mapLoaded, setMapLoaded] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setMapLoaded(true), 1500)
    return () => clearTimeout(timer)
  }, [])

  // Build clean full address
  const fullAddress = [addressLine, ward, province].filter(Boolean).join(', ')

  // Destination query for Google Maps
  const destinationQuery =
    latitude != null && longitude != null
      ? `${latitude},${longitude}`
      : encodeURIComponent(fullAddress)

  // Direct turn-by-turn directions URL
  const googleMapsDirectionsUrl = `https://www.google.com/maps/dir/?api=1&destination=${destinationQuery}`

  // Google Maps place/search URL
  const googleMapsSearchUrl = `https://www.google.com/maps/search/?api=1&query=${destinationQuery}`

  // Google Maps embed URL
  const googleMapsEmbedUrl =
    latitude != null && longitude != null
      ? `https://maps.google.com/maps?q=${latitude},${longitude}&hl=vi&z=16&output=embed`
      : `https://maps.google.com/maps?q=${encodeURIComponent(fullAddress)}&hl=vi&z=16&output=embed`

  function showToast(msg: string) {
    setToastMessage(msg)
    setTimeout(() => setToastMessage(null), 3000)
  }

  async function handleCopyAddress() {
    try {
      await navigator.clipboard.writeText(fullAddress)
      setCopied(true)
      showToast('Đã sao chép địa chỉ vào clipboard!')
      setTimeout(() => setCopied(false), 2500)
    } catch {
      showToast('Không thể sao chép địa chỉ.')
    }
  }

  async function handleShare() {
    if (typeof navigator !== 'undefined' && navigator.share) {
      try {
        await navigator.share({
          title: venueName,
          text: `Chỉ đường đến ${venueName} - ${fullAddress}`,
          url: window.location.href,
        })
        return
      } catch {
        // Fallback to clipboard if cancelled or fails
      }
    }
    try {
      await navigator.clipboard.writeText(window.location.href)
      showToast('Đã sao chép liên kết vào clipboard!')
    } catch {
      showToast('Không thể chia sẻ liên kết.')
    }
  }

  return (
    <section
      aria-label="Bản đồ và chỉ đường"
      className={`relative overflow-hidden rounded-3xl border border-slate-200/80 bg-white p-5 sm:p-7 shadow-sm transition hover:shadow-md ${className}`}
    >
      {/* 1. Header with title & main action buttons */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-3.5">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-700 ring-1 ring-emerald-100">
            <Compass className="h-6 w-6" />
          </div>
          <div>
            <h2 className="text-xl sm:text-2xl font-bold tracking-tight text-slate-900">{title}</h2>
            <p className="mt-1 text-sm text-slate-500 leading-relaxed">{subtitle}</p>
          </div>
        </div>

        {/* Action button row */}
        <div className="flex flex-wrap items-center gap-2.5 pt-1 sm:pt-0">
          {/* Main button: Dẫn đường Google Maps (SportHub Emerald) */}
          <a
            href={googleMapsDirectionsUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 rounded-full bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-emerald-700 active:scale-[0.98]"
            title="Mở Google Maps để điều hướng chỉ đường tức thì"
          >
            <Navigation className="h-4 w-4 shrink-0 fill-current" />
            <span>Dẫn đường Google Maps</span>
          </a>

          {/* Secondary button: Sao chép địa chỉ */}
          <button
            type="button"
            onClick={handleCopyAddress}
            className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3.5 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 active:scale-[0.98]"
            title="Sao chép địa chỉ này"
          >
            {copied ? (
              <Check className="h-4 w-4 text-emerald-600" />
            ) : (
              <Copy className="h-4 w-4 text-slate-500" />
            )}
            <span>{copied ? 'Đã sao chép' : 'Sao chép địa chỉ'}</span>
          </button>

          {/* Share button */}
          {showShare && (
            <button
              type="button"
              onClick={handleShare}
              className="inline-flex h-10 w-10 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-600 transition hover:bg-slate-50 hover:text-slate-900 active:scale-[0.98]"
              title="Chia sẻ vị trí"
              aria-label="Chia sẻ vị trí"
            >
              <Share2 className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>

      {/* 2. Address highlight bar */}
      <div className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-100 bg-slate-50/80 p-4 sm:p-5 transition">
        <div className="flex items-start gap-3.5 min-w-0">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white shadow-xs text-emerald-600 ring-1 ring-emerald-100">
            <MapPin className="h-5 w-5 fill-emerald-100 text-emerald-600" />
          </div>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-bold text-slate-900 text-base sm:text-lg">{venueName}</span>
              <span className="inline-flex items-center gap-1 rounded-full border border-amber-200/90 bg-amber-50 px-2.5 py-0.5 text-xs font-semibold text-amber-800">
                <Check className="h-3 w-3 text-amber-700" />
                Địa chỉ chính xác
              </span>
            </div>
            <p className="mt-1 text-sm text-slate-600 select-all leading-normal break-words">
              {fullAddress}
            </p>
          </div>
        </div>

        {/* Quick copy icon button */}
        <button
          type="button"
          onClick={handleCopyAddress}
          className="shrink-0 rounded-xl border border-slate-200 bg-white p-2.5 text-slate-500 shadow-xs transition hover:bg-slate-50 hover:text-slate-800"
          title="Sao chép địa chỉ nhanh"
          aria-label="Sao chép địa chỉ nhanh"
        >
          {copied ? <Check className="h-4 w-4 text-emerald-600" /> : <Copy className="h-4 w-4" />}
        </button>
      </div>

      {/* 3. Interactive Map view */}
      <div className="relative mt-4 h-[380px] sm:h-[420px] w-full overflow-hidden rounded-2xl border border-slate-200/80 bg-slate-100 shadow-inner">
        {/* "Open in Maps" overlay button at top-left */}
        <a
          href={googleMapsSearchUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="absolute top-3 left-3 z-10 inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white/95 px-3 py-1.5 text-xs font-semibold text-slate-700 shadow-md backdrop-blur-xs transition hover:bg-white hover:text-emerald-700"
        >
          <span>Mở bản đồ</span>
          <ExternalLink className="h-3.5 w-3.5 text-slate-400" />
        </a>

        {/* Loading placeholder skeleton behind iframe */}
        {!mapLoaded && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-slate-100/90 text-slate-500 pointer-events-none transition-opacity duration-300">
            <div className="h-7 w-7 animate-spin rounded-full border-2 border-slate-300 border-t-emerald-600" />
            <p className="text-xs font-medium">Đang tải bản đồ Google Maps…</p>
          </div>
        )}

        {/* Embedded Google Map iframe */}
        <iframe
          src={googleMapsEmbedUrl}
          title={`Bản đồ ${venueName}`}
          width="100%"
          height="100%"
          style={{ border: 0 }}
          loading="eager"
          allowFullScreen
          referrerPolicy="no-referrer-when-downgrade"
          onLoad={() => setMapLoaded(true)}
          className="h-full w-full"
        />
      </div>

      {/* Floating feedback toast */}
      {toastMessage && (
        <div
          role="status"
          aria-live="polite"
          className="fixed bottom-6 left-1/2 z-50 -translate-x-1/2 flex items-center gap-2 rounded-full bg-slate-900 px-5 py-2.5 text-sm font-medium text-white shadow-xl animate-in fade-in slide-in-from-bottom-3 duration-200"
        >
          <Check className="h-4 w-4 text-emerald-400" />
          <span>{toastMessage}</span>
        </div>
      )}
    </section>
  )
}
