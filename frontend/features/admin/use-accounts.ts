'use client'
import { AdminAccount } from '@/features/admin/admin-account-types'
import { useApi } from '@/lib/use-api'
import { useState } from 'react'
export const ACCOUNTS_PAGE_SIZE = 20
export type AccountPage = {
  content: AdminAccount[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}
export function useAccounts() {
  // Filters and page live in the query; the server returns one page plus the full match count.
  const [filters, setFilters] = useState('')
  const [page, setPage] = useState(0)
  const query = new URLSearchParams(filters)
  query.set('page', String(page))
  query.set('size', String(ACCOUNTS_PAGE_SIZE))
  const path = `/admin/accounts?${query}`
  const r = useApi<AccountPage>(path)
  function reset() {
    setFilters('')
    setPage(0)
  }

  function search(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    const p = new URLSearchParams()
    for (const key of ['q', 'status', 'role']) {
      const v = String(f.get(key) || '').trim()
      if (v) p.set(key, v)
    }
    setFilters(p.toString())
    setPage(0)
  }

  // Platform-wide counts by status, independent of the paged list and its filters.
  const stats = useApi<{ status: string; total: number }[]>('/admin/accounts/statistics')
  const count = (...statuses: string[]) =>
    stats.data
      ?.filter((x) => statuses.includes(x.status))
      .reduce((n, x) => n + Number(x.total), 0) ?? 0
  const totalUsers = stats.data?.reduce((n, x) => n + Number(x.total), 0) ?? 0
  const activeUsers = count('ACTIVE')
  const restrictedUsers = count('LOCKED', 'SUSPENDED', 'DEACTIVATED')
  const pendingUsers = count('PENDING_VERIFICATION')

  return {
    path,
    page,
    setPage,
    reset,
    r,
    search,
    stats,
    totalUsers,
    activeUsers,
    restrictedUsers,
    pendingUsers,
  }
}
