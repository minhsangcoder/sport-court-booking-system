'use client'
import Image from 'next/image'
import { useQrImageDecoder } from './use-qr-image-decoder'

import { Button } from '@/components/ui/button'
import { AlertCircle, Camera, CheckCircle2, Loader2, QrCode, UploadCloud, X } from 'lucide-react'
import React, { useCallback, useEffect, useRef, useState } from 'react'
import { CameraQrScannerModal } from './lazy-camera-qr-scanner'
import { extractInviteCode } from './qr-parser'

interface QrFileUploadProps {
  onCodeDetected: (code: string) => void
  busy?: boolean
}

export function QrFileUpload({ onCodeDetected, busy = false }: QrFileUploadProps) {
  const { decode, cancelDecode } = useQrImageDecoder()
  const preview = useRef<string | null>(null)
  useEffect(
    () => () => {
      if (preview.current) URL.revokeObjectURL(preview.current)
    },
    [],
  )
  const [isDragging, setIsDragging] = useState(false)
  const [isScanning, setIsScanning] = useState(false)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [scanError, setScanError] = useState<string | null>(null)
  const [scanSuccessMsg, setScanSuccessMsg] = useState<string | null>(null)
  const [isCameraOpen, setIsCameraOpen] = useState(false)

  const fileInputRef = useRef<HTMLInputElement>(null)

  const processFile = useCallback(
    async (file: File) => {
      if (!file.type.startsWith('image/') && !file.name.endsWith('.svg')) {
        setScanError('Vui lòng chọn tệp hình ảnh (PNG, JPG, WEBP, SVG).')
        return
      }

      setScanError(null)
      setScanSuccessMsg(null)
      setIsScanning(true)

      // Create local preview
      if (preview.current) URL.revokeObjectURL(preview.current)
      const objectUrl = URL.createObjectURL(file)
      preview.current = objectUrl
      setPreviewUrl(objectUrl)

      const operation = decode(file)
      try {
        const rawResult = await operation.promise
        const code = extractInviteCode(rawResult)

        if (!code) {
          throw new Error('Mã QR này không chứa mã mời hợp lệ.')
        }

        setScanSuccessMsg('Đã nhận diện mã QR thành công!')
        onCodeDetected(code)
      } catch (err: unknown) {
        if (!operation.active()) return
        const error = err as { message?: string }
        setScanError(
          error.message || 'Không tìm thấy mã QR trong ảnh. Vui lòng thử ảnh khác rõ nét hơn.',
        )
      } finally {
        if (operation.active()) setIsScanning(false)
      }
    },
    [onCodeDetected, decode],
  )

  // Support pasting image from clipboard (Ctrl + V)
  useEffect(() => {
    function handlePaste(e: ClipboardEvent) {
      const items = e.clipboardData?.items
      if (!items) return

      for (let i = 0; i < items.length; i++) {
        if (items[i].type.indexOf('image') !== -1) {
          const file = items[i].getAsFile()
          if (file) {
            processFile(file)
            break
          }
        }
      }
    }

    window.addEventListener('paste', handlePaste)
    return () => window.removeEventListener('paste', handlePaste)
  }, [processFile])

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) {
      processFile(file)
    }
    // Reset file input value to allow selecting same file again if desired
    e.target.value = ''
  }

  function handleDragOver(e: React.DragEvent) {
    e.preventDefault()
    e.stopPropagation()
    setIsDragging(true)
  }

  function handleDragLeave(e: React.DragEvent) {
    e.preventDefault()
    e.stopPropagation()
    setIsDragging(false)
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault()
    e.stopPropagation()
    setIsDragging(false)

    const file = e.dataTransfer.files?.[0]
    if (file) {
      processFile(file)
    }
  }

  function handleCameraScan(rawResult: string) {
    setIsCameraOpen(false)
    const code = extractInviteCode(rawResult)
    if (code) {
      setScanSuccessMsg('Đã quét mã QR từ camera thành công!')
      setScanError(null)
      onCodeDetected(code)
    } else {
      setScanError('Mã QR từ camera không hợp lệ.')
    }
  }

  function clearSelection() {
    cancelDecode()
    setIsScanning(false)
    preview.current = null
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl)
    }
    setPreviewUrl(null)
    setScanError(null)
    setScanSuccessMsg(null)
  }

  return (
    <div className="p-5 transition hover:border-slate-300 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
      {/* Hidden file input */}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*,.svg"
        className="hidden"
        onChange={handleFileChange}
        disabled={busy || isScanning}
      />

      {/* Camera scanner modal */}
      <CameraQrScannerModal
        isOpen={isCameraOpen}
        onClose={() => setIsCameraOpen(false)}
        onScanSuccess={handleCameraScan}
      />

      {/* Header with Camera Button */}
      <div className="flex flex-wrap items-center justify-between gap-3 pb-3">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-50 text-emerald-700">
            <QrCode className="h-4 w-4" />
          </div>
          <div>
            <h3 className="text-sm font-bold text-slate-900">Quét mã QR mời tham gia</h3>
            <p className="text-xs text-slate-500">
              Tải ảnh QR từ máy, chụp màn hình hoặc dùng camera
            </p>
          </div>
        </div>

        {/* Quick Camera Action */}
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => setIsCameraOpen(true)}
          disabled={busy || isScanning}
          className="gap-1.5 rounded-full border-emerald-200 bg-emerald-50/50 text-emerald-800 hover:bg-emerald-100 hover:text-emerald-900 text-xs font-semibold"
        >
          <Camera className="h-3.5 w-3.5 text-emerald-600" />
          <span>Quét bằng Camera</span>
        </Button>
      </div>

      {/* Upload Dropzone */}
      <div
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            fileInputRef.current?.click()
          }
        }}
        className={`relative flex min-h-[140px] cursor-pointer flex-col items-center justify-center rounded-2xl border border-slate-200/90-2 border-dashed p-4 text-center transition ${
          isDragging
            ? 'border-emerald-500 bg-emerald-50/70 scale-[0.99]'
            : 'border-slate-200 bg-slate-50/50 hover:border-emerald-400 hover:bg-emerald-50/20'
        } ${busy || isScanning ? 'pointer-events-none opacity-70' : ''}`}
      >
        {isScanning ? (
          <div className="flex flex-col items-center justify-center gap-2 py-4 text-emerald-700">
            <Loader2 className="h-8 w-8 animate-spin" />
            <p className="text-xs font-semibold">Đang đọc và phân tích mã QR từ ảnh…</p>
          </div>
        ) : previewUrl ? (
          <div className="flex items-center gap-3 py-1" onClick={(e) => e.stopPropagation()}>
            {/* Thumbnail */}
            <div className="relative h-16 w-16 shrink-0 overflow-hidden rounded-lg border border-slate-200 bg-white p-1 shadow-xs">
              <Image
                unoptimized
                width={64}
                height={64}
                src={previewUrl}
                alt="Ảnh QR đã chọn"
                className="h-full w-full object-contain"
              />
            </div>
            <div className="text-left">
              <p className="text-xs font-medium text-slate-700">Ảnh đã chọn</p>
              <div className="mt-1 flex items-center gap-2">
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
                  onClick={clearSelection}
                  className="text-xs text-slate-500 hover:text-red-600 inline-flex items-center gap-0.5"
                >
                  <X className="h-3 w-3" /> Xóa
                </button>
              </div>
            </div>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-1.5 py-2">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-white text-slate-600 shadow-xs ring-1 ring-slate-200">
              <UploadCloud className="h-5 w-5 text-emerald-600" />
            </div>
            <p className="text-xs font-semibold text-slate-800">
              <span className="font-semibold text-emerald-700 hover:underline">
                Bấm để tải ảnh QR
              </span>{' '}
              hoặc kéo thả vào đây
            </p>
            <p className="text-[11px] text-slate-500">
              Hỗ trợ PNG, JPG, WEBP, SVG hoặc dán ảnh trực tiếp (
              <kbd className="rounded border bg-white px-1 py-0.5 text-[10px] font-mono text-slate-600">
                Ctrl + V
              </kbd>
              )
            </p>
          </div>
        )}
      </div>

      {/* Success alert */}
      {scanSuccessMsg && (
        <div className="mt-3 flex items-center gap-2 rounded-2xl bg-emerald-50 px-3.5 py-2 text-xs font-medium text-emerald-800 border border-emerald-100 animate-in fade-in">
          <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-600" />
          <span>{scanSuccessMsg}</span>
        </div>
      )}

      {/* Error alert */}
      {scanError && (
        <div className="mt-3 flex items-start gap-2 rounded-2xl bg-rose-50 px-3.5 py-2.5 text-xs text-rose-800 border border-rose-100 animate-in fade-in">
          <AlertCircle className="h-4 w-4 shrink-0 text-rose-600 mt-0.5" />
          <span>{scanError}</span>
        </div>
      )}
    </div>
  )
}
