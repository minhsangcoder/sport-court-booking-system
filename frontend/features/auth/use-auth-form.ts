'use client'
import type { FacilityLocation } from '@/features/facility/location-picker'
import { ApiError, authApi } from '@/lib/api'
import { useRouter, useSearchParams } from 'next/navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { accountFrom } from './account-payload'
import type { Mode } from './auth-mode'
import { postLoginPath } from './google-auth-state'
export function useAuthForm({ mode: initialMode = 'login' }: { mode?: Mode }) {
  const router = useRouter()
  const params = useSearchParams()
  const [selection, setSelection] = useState({ initial: initialMode, mode: initialMode })
  let mode = selection.mode
  if (selection.initial !== initialMode) {
    mode = initialMode
    setSelection({ initial: initialMode, mode: initialMode })
  }
  const setMode = useCallback((mode: Mode) => setSelection((value) => ({ ...value, mode })), [])

  useEffect(() => {
    const handlePopState = () => {
      const path = window.location.pathname
      if (path === '/login') setMode('login')
      else if (path === '/register') setMode('register')
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [setMode])

  function switchMode(newMode: 'login' | 'register') {
    if (newMode === mode) return
    setMode(newMode)
    setError('')
    setSuccess('')
    setFieldErrors({})
    window.history.pushState(null, '', `/${newMode}${window.location.search}`)
  }

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [showPassword, setShowPassword] = useState(false)
  const [rememberMe, setRememberMe] = useState(true)

  const [identifierInput, setIdentifierInput] = useState('')
  const [passwordInput, setPasswordInput] = useState('')

  const [applyAsOwner, setApplyAsOwner] = useState(false)
  const [ownerVisited, setOwnerVisited] = useState(false)
  const [location, setLocation] = useState<FacilityLocation>(null)
  const signupRetry = useRef<{ fingerprint: string; key: string } | null>(null)

  const googleError =
    mode === 'login'
      ? ({
          google_not_configured:
            'Đăng nhập Google chưa được cấu hình. Bạn có thể dùng thư điện tử hoặc số điện thoại để tiếp tục.',
          google_email_unverified:
            'Xác minh thư điện tử bằng phương thức đăng nhập hiện tại trước khi liên kết Google.',
          google_account_unavailable:
            'Không thể liên kết tài khoản Google với tài khoản SportHub này.',
        }[params.get('authError') ?? ''] ??
        (params.has('authError') ? 'Không thể đăng nhập bằng Google. Vui lòng thử lại.' : ''))
      : ''

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    setSuccess('')
    setFieldErrors({})
    const values = new FormData(event.currentTarget)
    const value = (name: string) => String(values.get(name) ?? '').trim()

    try {
      if (mode === 'login') {
        const session = await authApi.login(
          value('identifier'),
          String(values.get('password') ?? ''),
        )
        const next = params.get('next')
        if (
          session.user.roles.includes('STAFF') &&
          !session.user.roles.includes('OWNER') &&
          !session.user.roles.includes('ADMIN')
        ) {
          router.push(next && next !== '/' ? postLoginPath(next) : '/staff/bookings')
        } else {
          router.push(postLoginPath(next))
        }
      } else if (mode === 'register') {
        const account = accountFrom(values)
        let result
        if (applyAsOwner) {
          const { ownerSignupFrom } = await import('./owner-signup-payload')
          let payload
          try {
            payload = ownerSignupFrom(values, location)
          } catch (error) {
            if (error instanceof Error && 'field' in error)
              setFieldErrors({ [String(error.field)]: error.message })
            throw error
          }
          if (signupRetry.current?.fingerprint !== payload.fingerprint)
            signupRetry.current = { fingerprint: payload.fingerprint, key: crypto.randomUUID() }
          result = await authApi.registerOwner(payload.multipart, signupRetry.current!.key)
        } else result = await authApi.register(account)
        const query = new URLSearchParams({ challengeId: result.verificationChallengeId })
        if (result.ownerApplication) {
          query.set('ownerApplicationId', result.ownerApplication.id)
          query.set('ownerState', result.ownerApplication.state)
        }
        if (result.accountStatus === 'ACTIVE') {
          router.push(
            result.ownerApplication
              ? `/login?next=${encodeURIComponent(`/owner/application/${result.ownerApplication.id}`)}`
              : '/login',
          )
        } else {
          router.push(`/verify?${query}`)
        }
      } else if (mode === 'verify') {
        await authApi.verify(
          params.get('token')
            ? { verificationToken: params.get('token')! }
            : { challengeId: value('challengeId'), code: value('code') },
        )
        setSuccess('Xác minh thành công. Bạn có thể đăng nhập ngay bây giờ.')
      } else if (mode === 'forgot') {
        await authApi.forgot(value('identifier'))
        setSuccess(
          'Nếu tài khoản hợp lệ, hướng dẫn đã được gửi qua thư điện tử hoặc SMS đã cấu hình.',
        )
      } else {
        await authApi.reset({
          challengeId: value('challengeId'),
          code: value('code'),
          newPassword: String(values.get('password') ?? ''),
        })
        setSuccess('Mật khẩu đã được cập nhật thành công. Vui lòng đăng nhập lại.')
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Có lỗi xảy ra.')
      if (err instanceof ApiError) {
        setFieldErrors(
          Object.fromEntries(
            err.fieldErrors.map((item) => {
              const field = item.field.replace(/^ownerApplication\.(legal|facility|setup)\./, '')
              return [['email', 'phone'].includes(item.field) ? 'identifier' : field, item.message]
            }),
          ),
        )
      }
    } finally {
      setBusy(false)
    }
  }

  const isExpandedOwner = mode === 'register' && applyAsOwner

  return {
    mode,
    switchMode,
    busy,
    error,
    success,
    fieldErrors,
    showPassword,
    setShowPassword,
    rememberMe,
    setRememberMe,
    identifierInput,
    setIdentifierInput,
    passwordInput,
    setPasswordInput,
    applyAsOwner,
    setApplyAsOwner,
    ownerVisited,
    setOwnerVisited,
    location,
    setLocation,
    googleError,
    submit,
    params,
    isExpandedOwner,
  }
}
export type AuthModel = ReturnType<typeof useAuthForm>
