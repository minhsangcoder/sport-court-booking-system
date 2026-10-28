'use client'

import { Button } from '@/components/ui/button'
import { AlertTriangle, Trash2, X } from 'lucide-react'

interface DeleteGroupDialogProps {
  isOpen: boolean
  onClose: () => void
  onConfirm: () => void
  groupName: string
  isBusy?: boolean
}

export function DeleteGroupDialog({
  isOpen,
  onClose,
  onConfirm,
  groupName,
  isBusy = false,
}: DeleteGroupDialogProps) {
  if (!isOpen) return null

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-group-dialog-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4 backdrop-blur-xs animate-in fade-in duration-200"
    >
      <div className="relative w-full max-w-md overflow-hidden rounded-3xl bg-white p-6 shadow-2xl ring-1 ring-slate-200">
        {/* Close button */}
        <button
          type="button"
          onClick={onClose}
          disabled={isBusy}
          className="absolute top-4 right-4 flex h-8 w-8 items-center justify-center rounded-full text-slate-400 hover:bg-slate-100 hover:text-slate-700"
          aria-label="Đóng"
        >
          <X className="h-5 w-5" />
        </button>

        {/* Warning Icon */}
        <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-rose-50 text-rose-600 ring-1 ring-rose-100">
          <Trash2 className="h-6 w-6" />
        </div>

        {/* Content */}
        <h2
          id="delete-group-dialog-title"
          className="mt-4 text-xl font-bold tracking-tight text-slate-900"
        >
          Xóa nhóm khỏi danh sách cá nhân?
        </h2>

        <p className="mt-2 text-sm text-slate-600 leading-relaxed">
          Bạn sắp xóa nhóm <strong className="text-slate-900 font-semibold">{groupName}</strong>{' '}
          khỏi danh sách Nhóm đặt sân của bạn.
        </p>

        <div className="mt-3.5 flex items-start gap-2.5 rounded-xl bg-amber-50 p-3 border border-amber-200/80 text-xs text-amber-900 leading-relaxed">
          <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600 mt-0.5" />
          <span>
            Nhóm sẽ bị ẩn khỏi trang của bạn. Bạn vẫn có thể xem lại hoặc khôi phục bất cứ lúc nào
            trong mục <strong>Đã xóa / Ẩn</strong>.
          </span>
        </div>

        {/* Actions */}
        <div className="mt-6 flex items-center justify-end gap-3">
          <Button
            type="button"
            variant="outline"
            onClick={onClose}
            disabled={isBusy}
            className="rounded-full px-5 font-semibold text-slate-700"
          >
            Hủy
          </Button>
          <Button
            type="button"
            onClick={onConfirm}
            disabled={isBusy}
            className="rounded-full bg-rose-600 px-5 font-semibold text-white shadow-xs hover:bg-rose-700 active:scale-[0.98]"
          >
            Xác nhận xóa
          </Button>
        </div>
      </div>
    </div>
  )
}
