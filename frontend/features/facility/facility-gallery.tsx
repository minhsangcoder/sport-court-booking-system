'use client'
import { card, emeraldNotice, primaryPill, outlinePill, redNotice } from './owner-ui'
import { Button } from '@/components/ui/button'
import type { useFacilityOverview } from '@/features/facility/use-facility-overview'
import { AlertCircle, RefreshCw, Trash2, Upload } from 'lucide-react'
import Image from 'next/image'
export function FacilityGallery({
  model,
}: {
  model: Pick<
    ReturnType<typeof useFacilityOverview>,
    'replacement' | 'upload' | 'busy' | 'setReplacement' | 'message' | 'error' | 'images' | 'remove'
  >
}) {
  const { replacement, upload, busy, setReplacement, message, error, images, remove } = model
  return (
    <div className={`${card} home-rise p-5 sm:p-6`}>
      <div className="flex flex-col justify-between gap-2 sm:flex-row sm:items-center">
        <div>
          <h3 className="text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
            Thư viện ảnh cơ sở
          </h3>
          <p className="text-xs text-slate-500">Định dạng JPG/PNG, tối đa 10 MB mỗi ảnh.</p>
        </div>
        {replacement && (
          <div className="inline-flex items-center gap-2 rounded-full bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-800">
            <AlertCircle size={14} />
            <span>Đang ở chế độ thay thế ảnh</span>
          </div>
        )}
      </div>

      {/* Upload Form */}
      <form
        onSubmit={upload}
        className="mt-5 rounded-2xl border border-dashed border-slate-300 bg-slate-50/50 p-5 dark:border-slate-700 dark:bg-slate-800/30"
      >
        <div className="flex flex-wrap items-center gap-3">
          <input
            name="file"
            type="file"
            accept="image/png,image/jpeg"
            required
            disabled={busy}
            className="text-xs text-slate-600 file:mr-3 file:cursor-pointer file:rounded-full file:border-0 file:bg-emerald-50 file:px-4 file:py-2 file:text-xs file:font-semibold file:text-emerald-800 hover:file:bg-emerald-100"
          />
          <Button type="submit" disabled={busy} className={primaryPill}>
            <Upload size={14} />
            <span>{busy ? 'Đang xử lý…' : replacement ? 'Lưu ảnh thay thế' : 'Tải ảnh lên'}</span>
          </Button>
          {replacement && (
            <Button
              variant="outline"
              type="button"
              disabled={busy}
              onClick={() => setReplacement(null)}
              className={outlinePill}
            >
              Hủy thay ảnh
            </Button>
          )}
        </div>
      </form>

      {/* Feedback Messages */}
      {message && (
        <p role="status" className={`${emeraldNotice} mt-4`}>
          {message}
        </p>
      )}
      {(error || images.error) && (
        <p role="alert" className={`${redNotice} mt-4`}>
          {error || images.error}
        </p>
      )}

      {/* Images Grid */}
      {images.loading && <p className="mt-6 text-xs text-slate-400">Đang tải thư viện ảnh…</p>}

      {!images.loading && images.data?.length === 0 && (
        <p className="mt-6 text-xs text-slate-400">Chưa có ảnh nào được tải lên cho cơ sở này.</p>
      )}

      <div className="mt-6 grid grid-cols-1 gap-5 sm:grid-cols-2 md:grid-cols-3">
        {images.data?.map((image) => (
          <figure
            key={image.id}
            className="group relative overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm transition hover:-translate-y-0.5 hover:shadow-xl dark:border-slate-700 dark:bg-slate-800"
          >
            <div className="relative h-44 w-full bg-slate-100 dark:bg-slate-900">
              <Image
                unoptimized
                src={image.url}
                alt="Ảnh cơ sở"
                width={400}
                height={240}
                className="h-44 w-full object-cover transition-transform duration-700 group-hover:scale-105"
              />
            </div>
            <figcaption className="flex items-center justify-between border-t border-slate-100 p-3 dark:border-slate-700">
              <button
                type="button"
                onClick={() => setReplacement(image.id)}
                disabled={busy}
                className="inline-flex items-center gap-1 text-xs font-bold text-emerald-600 hover:text-emerald-700"
              >
                <RefreshCw size={12} />
                <span>Thay ảnh</span>
              </button>
              <button
                type="button"
                onClick={() => remove(image.id)}
                disabled={busy}
                className="inline-flex items-center gap-1 text-xs font-bold text-red-600 hover:text-red-700"
              >
                <Trash2 size={12} />
                <span>Xóa</span>
              </button>
            </figcaption>
          </figure>
        ))}
      </div>
    </div>
  )
}
