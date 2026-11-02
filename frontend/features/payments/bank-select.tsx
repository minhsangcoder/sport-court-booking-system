'use client'
import { fieldLabel, selectField } from '@/features/transfers/transfer-styles'
import { useApi } from '@/lib/use-api'
import type { BankCatalog } from './types'

/** The catalog's name for a BIN; the BIN itself until the catalog answers. */
export function BankName({ bin }: { bin: string }) {
  const catalog = useApi<BankCatalog>('/payments/banks')
  const bank = catalog.data?.banks.find((b) => b.bin === bin)
  return <>{bank ? `${bank.shortName} — ${bank.name}` : bin}</>
}

export function BankSelect({
  name = 'bankBin',
  defaultValue = '',
  disabled = false,
}: {
  name?: string
  defaultValue?: string
  disabled?: boolean
}) {
  const catalog = useApi<BankCatalog>('/payments/banks')
  return (
    <div className="space-y-2">
      <label className={fieldLabel}>
        Ngân hàng nhận
        {/* Remount once options exist: a select drops a defaultValue it has no option for. */}
        <select
          key={catalog.data ? 'ready' : 'loading'}
          name={name}
          required
          defaultValue={defaultValue}
          disabled={disabled || catalog.loading || !catalog.data}
          className={selectField}
        >
          <option value="">Chọn ngân hàng</option>
          {catalog.data?.banks.map((bank) => (
            <option key={bank.bin} value={bank.bin}>
              {bank.shortName} — {bank.name} ({bank.bin})
            </option>
          ))}
        </select>
      </label>
      {catalog.loading && (
        <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
          Đang tải danh mục ngân hàng…
        </p>
      )}
      {catalog.error && (
        <p role="alert" className="text-sm text-red-700 dark:text-red-300">
          {catalog.error}{' '}
          <button type="button" className="underline" onClick={catalog.reload}>
            Tải lại danh mục
          </button>
        </p>
      )}
      {catalog.data?.stale && (
        <p className="text-xs text-amber-800 dark:text-amber-200">
          Đang dùng danh mục cập nhật ngày{' '}
          {new Date(catalog.data.updatedAt).toLocaleDateString('vi-VN')}.
        </p>
      )}
    </div>
  )
}
