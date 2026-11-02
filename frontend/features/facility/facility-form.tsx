'use client'
import { Button } from '@/components/ui/button'
import { ApiError, api } from '@/lib/api'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { FacilityFields } from './facility-fields'
import { outlinePill, primaryPill, redNotice } from './owner-ui'
import { facilityFrom } from './facility-payload'
import type { FacilityLocation } from './location-picker'
import type { FacilityInput, FacilityProfile } from './types'
export function FacilityForm({
  facility,
  onSaved,
  redirectPath,
}: {
  facility?: FacilityProfile
  onSaved?: () => void
  redirectPath?: string
}) {
  const router = useRouter()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [location, setLocation] = useState<FacilityLocation>(() =>
    facility?.latitude != null && facility.longitude != null
      ? { latitude: facility.latitude, longitude: facility.longitude }
      : null,
  )
  const frozen = !!facility && ['PENDING_APPROVAL', 'REJECTED'].includes(facility.status)
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    setFields({})
    const form = new FormData(event.currentTarget)
    const body: FacilityInput = facilityFrom(form, location)
    try {
      const saved = await api<FacilityProfile>(
        facility ? `/owner/facilities/${facility.id}` : '/owner/facilities',
        { method: facility ? 'PUT' : 'POST', body: JSON.stringify(body) },
      )
      onSaved?.()
      router.push(redirectPath ?? `/owner/facilities/${saved.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể lưu cơ sở.')
      if (err instanceof ApiError)
        setFields(Object.fromEntries(err.fieldErrors.map((f) => [f.field, f.message])))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form
      onSubmit={save}
      className="owner-workspace home-rise grid min-w-0 grid-cols-1 gap-5 rounded-3xl border border-slate-200/90 bg-white p-5 shadow-xl shadow-slate-900/5 sm:p-7 md:grid-cols-2 dark:border-slate-800 dark:bg-slate-900"
    >
      <FacilityFields
        facility={facility}
        location={location}
        onLocationChange={setLocation}
        busy={busy}
        frozen={frozen}
        fields={fields}
      />
      {error && (
        <p role="alert" className={`${redNotice} md:col-span-2`}>
          {error}
        </p>
      )}
      <div className="flex gap-3 md:col-span-2">
        <Button disabled={busy} className={primaryPill} type="submit">
          {busy ? 'Đang lưu…' : facility ? 'Lưu thay đổi' : 'Tạo cơ sở'}
        </Button>
        <Button
          type="button"
          variant="outline"
          className={outlinePill}
          onClick={() => router.back()}
          disabled={busy}
        >
          Quay lại
        </Button>
      </div>
    </form>
  )
}
