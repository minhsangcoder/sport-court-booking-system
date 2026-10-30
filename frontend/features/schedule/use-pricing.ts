'use client'
import { useFacility } from '@/features/facility/facility-store'
import type { Category, Court } from '@/features/facility/types'
import { type Preview, type PriceRule } from '@/features/schedule/types'
import { api } from '@/lib/api'
import { type CourtType } from '@/lib/business-api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
import { pricingInput } from './pricing-input'
export function usePricing() {
  const { facility, basePath } = useFacility()
  const rules = useApi<PriceRule[]>(`/schedules/facilities/${facility.id}/pricing`)
  const courts = useApi<Court[]>(`/owner/facilities/${facility.id}/courts`)
  const categories = useApi<Category[]>('/sport-categories')
  const types = useApi<CourtType[]>(`/owner/facilities/${facility.id}/court-types`)
  const [editing, setEditing] = useState<PriceRule | null>(null)
  const [draft, setDraft] = useState<Preview | null>(null)
  const [dateMode, setDateMode] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  async function save(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    setMessage('')
    const form = e.currentTarget
    try {
      await api(`/schedules/facilities/${facility.id}/pricing${editing ? '/' + editing.id : ''}`, {
        method: editing ? 'PUT' : 'POST',
        headers: editing ? { 'If-Match': String(editing.version) } : undefined,
        body: JSON.stringify(pricingInput(new FormData(form), dateMode)),
      })
      rules.reload()
      form.reset()
      setEditing(null)
      setDraft(null)
      setMessage('Đã lưu quy tắc giá. Lịch đặt trước đó giữ nguyên giá đã chốt.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể lưu bảng giá.')
    } finally {
      setBusy(false)
    }
  }
  async function disable(id: string) {
    if (!window.confirm('Ngừng áp dụng quy tắc giá này cho lượt đặt mới?')) return
    setBusy(true)
    setError('')
    try {
      await api(`/schedules/facilities/${facility.id}/pricing/${id}`, { method: 'DELETE' })
      rules.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể ngừng giá.')
    } finally {
      setBusy(false)
    }
  }
  const selectClass =
    'h-11 w-full rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 text-sm font-medium text-slate-800 transition-all hover:border-slate-300 focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100'

  return {
    facility,
    basePath,
    rules,
    courts,
    categories,
    types,
    editing,
    setEditing,
    draft,
    setDraft,
    dateMode,
    setDateMode,
    busy,
    setBusy,
    error,
    setError,
    message,
    setMessage,
    save,
    disable,
    selectClass,
  }
}
