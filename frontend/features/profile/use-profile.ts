'use client'
import { ApiError, api, type Role, type UserProfile } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'
import { Building2, Shield, ShieldCheck, User } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
export function useProfile() {
  const { user } = useAuth()
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [copiedId, setCopiedId] = useState(false)

  // Form states with live preview
  const [fullName, setFullName] = useState('')
  const [dateOfBirth, setDateOfBirth] = useState('')
  const [avatarUrl, setAvatarUrl] = useState('')
  const [showAvatarPicker, setShowAvatarPicker] = useState(false)

  const [initialUser, setInitialUser] = useState<typeof user>(null)
  const copyTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const messageTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  // Apply a changed session before the form renders, without overwriting edits on every render.
  if (user !== initialUser) {
    setInitialUser(user)
    if (user) {
      setFullName(user.fullName || '')
      setDateOfBirth(user.dateOfBirth || '')
      setAvatarUrl(user.avatarUrl || '')
    }
  }
  useEffect(
    () => () => {
      clearTimeout(copyTimer.current)
      clearTimeout(messageTimer.current)
    },
    [],
  )

  const copyUserId = () => {
    if (!user?.id) return
    navigator.clipboard.writeText(user.id)
    setCopiedId(true)
    clearTimeout(copyTimer.current)
    copyTimer.current = setTimeout(() => setCopiedId(false), 2000)
  }

  const getInitials = (name?: string) => {
    if (!name) return 'U'
    const parts = name.trim().split(/\s+/)
    if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase()
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
  }

  const getRoleBadge = (role: Role) => {
    switch (role) {
      case 'ADMIN':
        return {
          label: 'Quản trị viên',
          icon: ShieldCheck,
          className:
            'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/40 dark:text-rose-300 dark:border-rose-800',
        }
      case 'OWNER':
        return {
          label: 'Chủ cơ sở sân',
          icon: Building2,
          className:
            'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/40 dark:text-amber-300 dark:border-amber-800',
        }
      case 'STAFF':
        return {
          label: 'Nhân viên quản lý',
          icon: Shield,
          className:
            'bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-300 dark:border-blue-800',
        }
      case 'CUSTOMER':
      default:
        return {
          label: 'Khách hàng',
          icon: User,
          className:
            'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-300 dark:border-emerald-800',
        }
    }
  }

  async function handleSave(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setMessage('')
    setError('')

    try {
      const updated = await api<UserProfile>('/users/me', {
        method: 'PATCH',
        body: JSON.stringify({
          fullName: fullName.trim(),
          avatarUrl: avatarUrl.trim() || null,
          dateOfBirth: dateOfBirth || null,
        }),
      })

      window.dispatchEvent(new CustomEvent('sporthub-session', { detail: updated }))
      setMessage('Hồ sơ của bạn đã được cập nhật thành công!')
      clearTimeout(messageTimer.current)
      messageTimer.current = setTimeout(() => setMessage(''), 4000)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Không thể lưu hồ sơ. Vui lòng thử lại.')
    } finally {
      setBusy(false)
    }
  }

  return {
    user,
    busy,
    setBusy,
    message,
    setMessage,
    error,
    setError,
    copiedId,
    setCopiedId,
    fullName,
    setFullName,
    dateOfBirth,
    setDateOfBirth,
    avatarUrl,
    setAvatarUrl,
    showAvatarPicker,
    setShowAvatarPicker,
    copyUserId,
    getInitials,
    getRoleBadge,
    handleSave,
  }
}
