'use client'
import {
  demoVnd,
  reportPreset,
  validateReportRange,
  type FacilityDemoReport,
  type ReportRange,
} from '@/features/facility/demo-report'
import { useFacility } from '@/features/facility/facility-store'
import { useApi } from '@/lib/use-api'
import { useEffect, useState } from 'react'
export function useFacilityReport() {
  const { facility } = useFacility()
  const [range, setRange] = useState<ReportRange>(() => reportPreset(facility.timezone, '7'))
  const [draft, setDraft] = useState(range)
  const [page, setPage] = useState(0)
  const [peerPage, setPeerPage] = useState(0)
  const [error, setError] = useState('')
  const [moneySource, setMoneySource] = useState<'ALL' | 'CASH' | 'BANK'>('ALL')
  const params = new URLSearchParams({
    ...range,
    page: String(page),
    size: '25',
    mode: 'REAL',
    moneySource,
    peerPage: String(peerPage),
  })
  const resource = useApi<FacilityDemoReport>(
    `/bookings/reports/facility/${facility.id}/dashboard?${params}`,
  )
  const reload = resource.reload
  const identity = facility.id + ':' + facility.timezone
  const [previousFacility, setPreviousFacility] = useState(identity)
  if (previousFacility !== identity) {
    setPreviousFacility(identity)
    const initial = reportPreset(facility.timezone, '7')
    setRange(initial)
    setDraft(initial)
    setPage(0)
    setPeerPage(0)
    setError('')
  }
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === 'sporthub:demo-money-changed') void reload()
    }
    window.addEventListener('sporthub:demo-money-changed', reload)
    window.addEventListener('focus', reload)
    window.addEventListener('storage', onStorage)
    return () => {
      window.removeEventListener('sporthub:demo-money-changed', reload)
      window.removeEventListener('focus', reload)
      window.removeEventListener('storage', onStorage)
    }
  }, [reload])
  function apply(next: ReportRange) {
    const message = validateReportRange(next)
    setError(message)
    if (!message) {
      setRange(next)
      setDraft(next)
      setPage(0)
      setPeerPage(0)
    }
  }
  const report = !resource.loading && !resource.error ? resource.data : null
  const mode = report?.mode
  const available = report?.moneyAvailability !== 'UNAVAILABLE'
  const amount = (value: string) => (available ? demoVnd(value) : 'Chưa có dữ liệu')
  const stats = report?.summary
  const money = stats?.money
  const timestamp = (value: string) =>
    new Date(value).toLocaleString('vi-VN', {
      timeZone: facility.timezone,
      dateStyle: 'short',
      timeStyle: 'short',
    })

  return {
    facility,
    range,
    setRange,
    draft,
    setDraft,
    page,
    setPage,
    peerPage,
    setPeerPage,
    error,
    setError,
    params,
    resource,
    apply,
    report,
    available,
    amount,
    stats,
    money,
    timestamp,
    mode,
    moneySource,
    setMoneySource: (next: 'ALL' | 'CASH' | 'BANK') => {
      setMoneySource(next)
      setPage(0)
      setPeerPage(0)
    },
  }
}
