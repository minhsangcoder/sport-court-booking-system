'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { CameraQrScannerModal } from '@/features/groups/lazy-camera-qr-scanner'
import { extractBookingIdFromToken, extractCheckinToken } from '@/features/groups/qr-parser'
import { AlertCircle, CheckCircle2, QrCode, RefreshCw, X } from 'lucide-react'
import { QrCheckinUpload } from './qr-checkin-upload'
import { useQrCheckin } from './use-qr-checkin'
interface QuickQrCheckinModalProps {
  isOpen: boolean
  onClose: () => void
  onSuccess?: (bookingId: string) => void
}
export function QuickQrCheckinModal({ isOpen, onClose, onSuccess }: QuickQrCheckinModalProps) {
  const model = useQrCheckin(isOpen, onSuccess)
  const {
    isSubmitting,
    detectedToken,
    setDetectedToken,
    detectedBookingId,
    setDetectedBookingId,
    scanError,
    setScanError,
    checkinSuccess,
    checkinMessage,
    isCameraOpen,
    setIsCameraOpen,
    resetState,
    executeCheckin,
  } = model
  if (!isOpen) return null

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="qr-modal-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4 backdrop-blur-xs animate-in fade-in duration-200"
    >
      <div className="relative w-full max-w-lg overflow-hidden rounded-3xl bg-white shadow-2xl ring-1 ring-slate-200">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-100 px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-50 text-emerald-700">
              <QrCode className="h-5 w-5" />
            </div>
            <div>
              <h2 id="qr-modal-title" className="text-lg font-extrabold text-slate-900">
                Xác nhận vào sân bằng mã QR
              </h2>
              <p className="text-xs text-slate-500">Tải ảnh mã QR của khách hoặc quét trực tiếp</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-full text-slate-400 hover:bg-slate-100 hover:text-slate-700"
            aria-label="Đóng"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Modal Body */}
        <div className="p-6 space-y-4">
          {checkinSuccess ? (
            /* Success State */
            <div className="flex flex-col items-center justify-center py-6 text-center animate-in zoom-in-95">
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100 text-emerald-600 shadow-inner">
                <CheckCircle2 className="h-10 w-10" />
              </div>
              <h3 className="mt-4 text-xl font-bold text-slate-900">
                Xác nhận vào sân thành công!
              </h3>
              <p className="mt-2 text-sm text-emerald-700 font-medium">{checkinMessage}</p>
              {detectedBookingId && (
                <p className="mt-1 text-xs text-slate-500 font-mono">
                  Mã lượt đặt: {detectedBookingId}
                </p>
              )}

              <div className="mt-6 flex flex-wrap gap-3">
                <Button
                  type="button"
                  onClick={resetState}
                  className="gap-2 bg-emerald-600 hover:bg-emerald-700 text-white"
                >
                  <RefreshCw className="h-4 w-4" /> Quét tiếp khách khác
                </Button>
                <Button type="button" variant="outline" onClick={onClose}>
                  Đóng
                </Button>
              </div>
            </div>
          ) : (
            /* Upload / Scan Area */
            <>
              <QrCheckinUpload model={model} />
              {/* Error Alert */}
              {scanError && (
                <div className="flex items-start gap-2.5 rounded-2xl border border-rose-200 bg-rose-50 p-3.5 text-xs text-rose-800 animate-in fade-in">
                  <AlertCircle className="h-4 w-4 shrink-0 text-rose-600 mt-0.5" />
                  <div className="flex-1">
                    <p className="font-semibold">Không thể xác nhận vào sân</p>
                    <p className="mt-0.5">{scanError}</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setScanError(null)}
                    className="text-rose-500 hover:text-rose-800"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>
              )}

              {/* Manual Confirmation if bookingId was resolved but needs click */}
              {detectedToken && detectedBookingId && !isSubmitting && !checkinSuccess && (
                <div className="rounded-2xl border border-emerald-200 bg-emerald-50/50 p-4">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-xs font-semibold text-emerald-800">
                        Đã nhận diện lượt đặt #{detectedBookingId.slice(0, 8).toUpperCase()}
                      </p>
                      <p className="text-[11px] text-slate-500 font-mono truncate max-w-xs">
                        {detectedBookingId}
                      </p>
                    </div>
                    <Button
                      type="button"
                      onClick={() => executeCheckin(detectedBookingId, detectedToken)}
                      className="bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-bold"
                    >
                      Xác nhận xác nhận vào sân
                    </Button>
                  </div>
                </div>
              )}

              {/* Fallback if token was read but bookingId could not be extracted automatically */}
              {detectedToken && !detectedBookingId && !isSubmitting && !checkinSuccess && (
                <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 space-y-2">
                  <p className="text-xs font-semibold text-amber-900">
                    Đã đọc được mã QR, vui lòng nhập mã lượt đặt cần xác nhận vào sân:
                  </p>
                  <form
                    className="flex gap-2"
                    onSubmit={(e) => {
                      e.preventDefault()
                      const bId = new FormData(e.currentTarget).get('manualBookingId') as string
                      if (bId && bId.trim()) {
                        executeCheckin(bId.trim(), detectedToken)
                      }
                    }}
                  >
                    <Input
                      name="manualBookingId"
                      placeholder="Nhập ID lượt đặt (UUID)"
                      required
                      className="text-xs"
                    />
                    <Button type="submit" size="sm" className="bg-emerald-600 text-white shrink-0">
                      Xác nhận vào sân
                    </Button>
                  </form>
                </div>
              )}
            </>
          )}
        </div>

        {/* Footer */}
        <div className="border-t border-slate-100 bg-slate-50 px-6 py-3 flex items-center justify-between text-xs text-slate-500">
          <span>Hệ thống tự động kích hoạt giờ chơi khi xác nhận vào sân</span>
          <Button variant="ghost" size="sm" onClick={onClose}>
            Đóng
          </Button>
        </div>
      </div>

      {/* Camera scanner modal */}
      {isCameraOpen && (
        <CameraQrScannerModal
          isOpen={isCameraOpen}
          onClose={() => setIsCameraOpen(false)}
          onScanSuccess={(raw) => {
            setIsCameraOpen(false)
            const token = extractCheckinToken(raw)
            if (token) {
              const bId = extractBookingIdFromToken(token)
              setDetectedToken(token)
              setDetectedBookingId(bId)
              if (bId) {
                executeCheckin(bId, token)
              }
            } else {
              setScanError('Mã QR từ camera không hợp lệ.')
            }
          }}
        />
      )}
    </div>
  )
}
