'use client'

import { LocalCourtImage } from '@/components/local-court-image'
import type { Media } from '@/features/facility/types'
import { money } from '@/features/schedule/types'
import { useApi } from '@/lib/use-api'
import { ArrowUpRight, Building2, Clock, Heart, MapPin, Navigation } from 'lucide-react'
import Link from 'next/link'
import { useState } from 'react'
import type { DiscoveryResult } from './types'

function getSportFallbackImage(sportName: string): string {
  const lower = (sportName || '').toLowerCase()
  if (lower.includes('pickleball')) return '/images/hero-pickleball.png'
  if (lower.includes('tennis')) return '/images/hero-tennis.png'
  if (lower.includes('cầu lông') || lower.includes('badminton') || lower.includes('paddle')) {
    return '/images/hero-paddle.png'
  }
  return '/images/court-aerial.jpg'
}

interface CourtCardProps {
  result: DiscoveryResult
  time: (value: string, zone: string) => string
}

export function CourtCard({ result, time }: CourtCardProps) {
  const f = result.facility
  const [isFavorite, setIsFavorite] = useState(false)
  const [imgError, setImgError] = useState(false)

  const imagesRes = useApi<Media[]>(`/facilities/${f.id}/images`)
  const uploadedImage =
    imagesRes.data?.find((m) => m.courtId === result.courtId) || imagesRes.data?.[0]

  const fallbackImage = getSportFallbackImage(result.sportName)
  const displayImageUrl = !imgError && uploadedImage?.url ? uploadedImage.url : fallbackImage
  const amenities = f.amenities.slice(0, 3)

  function follow(e: React.MouseEvent<HTMLElement>) {
    const r = e.currentTarget.getBoundingClientRect()
    e.currentTarget.style.setProperty('--mx', `${e.clientX - r.left}px`)
    e.currentTarget.style.setProperty('--my', `${e.clientY - r.top}px`)
  }

  return (
    <article
      onMouseMove={follow}
      className="group relative flex flex-col overflow-hidden rounded-3xl border border-slate-200/80 bg-white p-2 shadow-sm transition-all duration-500 ease-out hover:-translate-y-2 hover:border-emerald-300/70 hover:shadow-2xl hover:shadow-emerald-900/15 dark:border-slate-800 dark:bg-slate-900"
    >
      <div className="pointer-events-none absolute inset-0 z-20 rounded-3xl opacity-0 transition-opacity duration-300 group-hover:opacity-100 [background:radial-gradient(320px_circle_at_var(--mx,50%)_var(--my,50%),rgba(16,185,129,0.14),transparent_60%)]" />

      <div className="relative h-52 w-full overflow-hidden rounded-[1.25rem] bg-slate-900">
        <LocalCourtImage
          fill
          sizes="(max-width: 640px) 100vw, (max-width: 1024px) 50vw, 33vw"
          src={displayImageUrl}
          alt={`${result.courtName} - ${f.name}`}
          onError={() => setImgError(true)}
          className="h-full w-full object-cover transition-transform duration-[900ms] ease-out group-hover:scale-110"
          loading="lazy"
        />
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-slate-950/75 via-transparent to-slate-950/25" />

        <span
          className={`absolute left-3 top-3 z-10 rounded-full px-3 py-1 text-[11px] font-extrabold uppercase tracking-wide shadow-md bg-white/90 text-slate-900`}
        >
          {result.sportName}
        </span>

        <div className="absolute right-3 top-3 z-10 flex items-center gap-1.5">
          <Link
            href={`/facilities/${f.id}/directions`}
            className="flex size-8 items-center justify-center rounded-full bg-white/85 text-slate-700 backdrop-blur-md transition hover:scale-110 hover:bg-emerald-600 hover:text-white"
            title="Xem chỉ đường Google Maps"
            onClick={(e) => e.stopPropagation()}
          >
            <Navigation className="size-3.5" strokeWidth={1.75} />
          </Link>
          <button
            type="button"
            onClick={(e) => {
              e.preventDefault()
              e.stopPropagation()
              setIsFavorite(!isFavorite)
            }}
            className="flex size-8 cursor-pointer items-center justify-center rounded-full bg-white/85 text-slate-700 backdrop-blur-md transition hover:scale-110 hover:bg-white"
            title={isFavorite ? 'Bỏ thích' : 'Lưu vào yêu thích'}
            aria-label="Yêu thích"
          >
            <Heart
              strokeWidth={1.75}
              className={`size-3.5 transition ${isFavorite ? 'fill-rose-500 text-rose-500' : ''}`}
            />
          </button>
        </div>

        <div className="absolute bottom-3 left-3 right-3 z-10 flex items-end justify-between gap-2">
          <h3 className="min-w-0 text-lg font-extrabold leading-tight text-white drop-shadow">
            <span className="line-clamp-2">{result.courtName}</span>
          </h3>
          <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-white/20 px-2.5 py-1 text-[11px] font-semibold text-white backdrop-blur-md">
            <span className="relative flex size-2">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-300 opacity-75" />
              <span className="relative inline-flex size-2 rounded-full bg-emerald-300" />
            </span>
            {result.availableSlots} khung trống
          </span>
        </div>
      </div>

      <div className="flex flex-1 flex-col p-4 pb-3">
        <Link
          href={`/facilities/${f.id}`}
          className="inline-flex max-w-full items-center gap-1.5 text-sm font-semibold text-emerald-700 hover:underline dark:text-emerald-400"
        >
          <Building2 className="size-3.5 shrink-0" strokeWidth={1.75} />
          <span className="truncate">{f.name}</span>
        </Link>

        <div className="mt-3 space-y-1.5 text-xs text-slate-600 dark:text-slate-400">
          <p className="flex items-start gap-2">
            <MapPin className="mt-0.5 size-3.5 shrink-0 text-slate-400" strokeWidth={1.75} />
            <span className="line-clamp-2 leading-relaxed">
              {result.distanceKm != null && (
                <strong className="mr-1 font-semibold text-slate-900 dark:text-slate-100">
                  {result.distanceKm.toFixed(1)}km
                </strong>
              )}
              {f.addressLine}, {f.ward}, {f.province}
            </span>
          </p>
          <p className="flex items-center gap-2">
            <Clock className="size-3.5 shrink-0 text-slate-400" strokeWidth={1.75} />
            <span>
              Sớm nhất {time(result.firstStartsAt, f.timezone)} –{' '}
              {time(result.firstEndsAt, f.timezone)}
            </span>
          </p>
        </div>

        {amenities.length > 0 && (
          <div className="mt-3 flex flex-wrap gap-1.5">
            {amenities.map((a) => (
              <span
                key={a}
                className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-300"
              >
                {a}
              </span>
            ))}
            {f.amenities.length > amenities.length && (
              <span className="rounded-full bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300">
                +{f.amenities.length - amenities.length}
              </span>
            )}
          </div>
        )}

        <div className="mt-auto flex items-end justify-between gap-3 pt-5">
          <div className="min-w-0">
            <span className="block text-[11px] font-medium text-slate-400">Giá thuê từ</span>
            <div className="flex items-baseline gap-1 whitespace-nowrap">
              <span className="text-xl font-extrabold tracking-tight text-slate-900 dark:text-white">
                {money(result.fromPrice, result.currency)}
              </span>
              <span className="text-xs font-medium text-slate-500">/giờ</span>
            </div>
          </div>

          <Link
            href={`/facilities/${f.id}/courts/${result.courtId}?date=${result.date}`}
            aria-label={`Đặt lịch ${result.courtName}`}
            className="group/btn relative z-30 inline-flex h-11 shrink-0 items-center gap-2 rounded-full bg-slate-900 pl-5 pr-1.5 text-sm font-bold text-white transition-colors duration-300 hover:bg-emerald-600 dark:bg-emerald-600 dark:hover:bg-emerald-500"
          >
            Đặt lịch
            <span className="flex size-8 items-center justify-center rounded-full bg-white text-slate-900 transition-transform duration-300 group-hover/btn:rotate-45">
              <ArrowUpRight size={16} strokeWidth={2.25} />
            </span>
          </Link>
        </div>
      </div>
    </article>
  )
}
