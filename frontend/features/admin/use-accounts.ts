'use client'
import { AdminAccount } from '@/features/admin/admin-account-types'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
export function useAccounts() {
  const [path, setPath] = useState('/admin/accounts')
  const r = useApi<AdminAccount[]>(path)

  function search(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    const p = new URLSearchParams()
    for (const key of ['q', 'status', 'role']) {
      const v = String(f.get(key) || '').trim()
      if (v) p.set(key, v)
    }
    setPath(`/admin/accounts?${p.toString()}`)
  }

  // Platform-wide counts by status, independent of the 100-row list and its filters.
  const stats = useApi<{ status: string; total: number }[]>('/admin/accounts/statistics')
  const count = (...statuses: string[]) =>
    stats.data
      ?.filter((x) => statuses.includes(x.status))
      .reduce((n, x) => n + Number(x.total), 0) ?? 0
  const totalUsers = stats.data?.reduce((n, x) => n + Number(x.total), 0) ?? 0
  const activeUsers = count('ACTIVE')
  const restrictedUsers = count('LOCKED', 'SUSPENDED', 'DEACTIVATED')
  const pendingUsers = count('PENDING_VERIFICATION')

  return { path, setPath, r, search, stats, totalUsers, activeUsers, restrictedUsers, pendingUsers }
}
