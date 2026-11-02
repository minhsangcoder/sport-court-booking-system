'use client'
import { Button } from '@/components/ui/button'
import { Camera, Loader2, UploadCloud, X } from 'lucide-react'
import Image from 'next/image'
import type { QrCheckinModel } from './use-qr-checkin'
export function QrCheckinUpload({ model }: { model: QrCheckinModel }) {
  const {
    isDragging,
    setIsDragging,
    isScanning,
    isSubmitting,
    previewUrl,
    setIsCameraOpen,
    fileInputRef,
    resetState,
    processFile,
  } = model
  return (
    <>
      {/* Quick Camera Bar */}
      <div className="flex items-center justify-between rounded-2xl bg-slate-50 p-3">
        <div className="flex items-center gap-2 text-xs text-slate-600">
          <Camera className="h-4 w-4 text-emerald-600" />
          <span>Hoặc quét trực tiếp từ thiết bị</span>
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setIsCameraOpen(true)}
          disabled={isScanning || isSubmitting}
          className="gap-1.5 rounded-full border-emerald-200 bg-white text-emerald-800 hover:bg-emerald-50 text-xs font-semibold"
        >
          <Camera className="h-3.5 w-3.5 text-emerald-600" />
          <span>Mở Camera</span>
        </Button>
      </div>

      {/* File upload hidden */}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*,.svg"
        className="hidden"
        disabled={isScanning || isSubmitting}
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) processFile(file)
          e.target.value = ''
        }}
      />

      {/* Dropzone */}
      <div
        onDragOver={(e) => {
          e.preventDefault()
          e.stopPropagation()
          setIsDragging(true)
        }}
        onDragLeave={(e) => {
          e.preventDefault()
          e.stopPropagation()
          setIsDragging(false)
        }}
        onDrop={(e) => {
          e.preventDefault()
          e.stopPropagation()
          setIsDragging(false)
          const file = e.dataTransfer.files?.[0]
          if (file) processFile(file)
        }}
        onClick={() => fileInputRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            fileInputRef.current?.click()
          }
        }}
        className={`relative flex min-h-[170px] cursor-pointer flex-col items-center justify-center border-2 border-dashed p-6 text-center transition rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise${
          isDragging
            ? 'border-emerald-500 bg-emerald-50/70 scale-[0.99]'
            : 'border-slate-200 bg-slate-50/50 hover:border-emerald-400 hover:bg-emerald-50/20'
        } ${isScanning || isSubmitting ? 'pointer-events-none opacity-70' : ''}`}
      >
        {isScanning ? (
          <div className="flex flex-col items-center justify-center gap-2 py-4 text-emerald-700">
            <Loader2 className="h-8 w-8 animate-spin" />
            <p className="text-sm font-semibold">Đang đọc mã QR từ hình ảnh…</p>
          </div>
        ) : isSubmitting ? (
          <div className="flex flex-col items-center justify-center gap-2 py-4 text-emerald-700">
            <Loader2 className="h-8 w-8 animate-spin" />
            <p className="text-sm font-semibold">Đang thực hiện xác nhận vào sân…</p>
          </div>
        ) : previewUrl ? (
          <div className="flex flex-col items-center gap-3" onClick={(e) => e.stopPropagation()}>
            <div className="relative h-24 w-24 shrink-0 overflow-hidden rounded-xl border border-slate-200 bg-white p-2 shadow-xs">
              <Image
                src={previewUrl}
                alt="Ảnh QR đã chọn"
                width={400}
                height={400}
                unoptimized
                className="h-full w-full object-contain"
              />
            </div>
            <div className="text-center">
              <p className="text-xs font-semibold text-slate-700">Đã chọn ảnh mã QR</p>
              <div className="mt-1 flex items-center justify-center gap-3">
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  className="text-xs font-semibold text-emerald-700 hover:underline"
                >
                  Chọn ảnh khác
                </button>
                <span className="text-slate-300">·</span>
                <button
                  type="button"
                  onClick={resetState}
                  className="text-xs text-slate-500 hover:text-red-600 inline-flex items-center gap-0.5"
                >
                  <X className="h-3 w-3" /> Hủy
                </button>
              </div>
            </div>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-2">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-white text-emerald-600 shadow-xs ring-1 ring-slate-200">
              <UploadCloud className="h-6 w-6" />
            </div>
            <div>
              <p className="text-sm font-bold text-slate-800">
                <span className="font-semibold text-emerald-700 hover:underline">
                  Bấm để tải ảnh QR
                </span>{' '}
                hoặc kéo thả vào đây
              </p>
              <p className="mt-1 text-xs text-slate-500">
                Hỗ trợ PNG, JPG, WEBP, SVG hoặc nhấn{' '}
                <kbd className="rounded border bg-white px-1.5 py-0.5 text-[11px] font-mono text-slate-600 shadow-xs">
                  Ctrl + V
                </kbd>{' '}
                để dán ảnh
              </p>
            </div>
          </div>
        )}
      </div>
    </>
  )
}
