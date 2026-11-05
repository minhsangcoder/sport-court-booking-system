'use client'
import { confirmDanger } from '@/components/confirm-dialog'
import { useFacility } from '@/features/facility/facility-store'
import type { Court, Media } from '@/features/facility/types'
import { api } from '@/lib/api'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
export function useFacilityOverview() {
  const { facility, basePath, editable, applicationId } = useFacility()
  const images = useApi<Media[]>(`/owner/facilities/${facility.id}/images`)
  const courts = useApi<Court[]>(`/owner/facilities/${facility.id}/courts`)

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [replacement, setReplacement] = useState<string | null>(null)

  async function upload(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    setMessage('')
    const formElement = event.currentTarget

    try {
      await api(`/owner/facilities/${facility.id}/images${replacement ? '/' + replacement : ''}`, {
        method: replacement ? 'PUT' : 'POST',
        body: new FormData(formElement),
      })
      images.reload()
      formElement.reset()
      setMessage(replacement ? 'Đã thay thế ảnh thành công.' : 'Đã tải ảnh cơ sở lên thành công.')
      setReplacement(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể tải ảnh lên.')
    } finally {
      setBusy(false)
    }
  }

  async function remove(id: string) {
    if (!(await confirmDanger('Bạn có chắc muốn xóa ảnh này khỏi cơ sở?'))) return
    setBusy(true)
    setError('')
    try {
      await api(`/owner/facilities/${facility.id}/images/${id}`, { method: 'DELETE' })
      images.reload()
      setMessage('Đã xóa ảnh.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể xóa ảnh.')
    } finally {
      setBusy(false)
    }
  }

  const activeCourtsCount = courts.data?.filter((c) => c.enabled).length ?? 0
  const totalCourtsCount = courts.data?.length ?? 0

  return {
    facility,
    basePath,
    editable,
    applicationId,
    images,
    courts,
    busy,
    setBusy,
    error,
    setError,
    message,
    setMessage,
    replacement,
    setReplacement,
    upload,
    remove,
    activeCourtsCount,
    totalCourtsCount,
  }
}
