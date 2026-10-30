import { invalidateResources, setReadIdentity } from './read-state'
import { mutationResources } from './mutation-resources'

export type Role = 'CUSTOMER' | 'OWNER' | 'STAFF' | 'ADMIN'
export type UserProfile = {
  id: string
  fullName: string
  email: string | null
  phone: string | null
  roles: Role[]
  status: string
  emailVerified: boolean
  phoneVerified: boolean
  avatarUrl?: string | null
  dateOfBirth?: string | null
}
export type Session = {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: UserProfile
}
export type RegisterResult = {
  userId: string
  accountStatus: string
  verificationChallengeId: string
  verificationExpiresAt: string
  ownerApplication?: import('@/features/owner-application/types').OwnerApplication | null
  ownerSetupMessage?: string | null
}
type Envelope<T> = { data: T; message: string }
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public code?: string,
    public fieldErrors: { field: string; message: string }[] = [],
    public details?: Record<string, unknown>,
  ) {
    super(message)
  }
}
// Temporary typed adapter for the checked-in OpenAPI contracts. Tokens stay in memory.
let accessToken: string | null = null
let refreshFlight: Promise<Session> | null = null
let authGeneration = 0
let sessionUserId: string | null = null
export function isCurrentSessionUser(id: string | null) {
  return sessionUserId === id
}
function requireAuthGeneration(expected: number) {
  if (expected !== authGeneration) {
    const error = new Error('Authentication operation was superseded')
    error.name = 'AbortError'
    throw error
  }
}
function accept(session: Session | null) {
  sessionUserId = session?.user.id ?? null
  setReadIdentity(
    session
      ? JSON.stringify({ ...session.user, roles: [...(session.user.roles ?? [])].sort() })
      : '',
  )
  accessToken = session?.accessToken ?? null
  if (typeof window !== 'undefined')
    window.dispatchEvent(new CustomEvent('sporthub-session', { detail: session?.user ?? null }))
}
export async function api<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
  const requestGeneration = authGeneration
  options.signal?.throwIfAborted()
  const method = (options.method ?? 'GET').toUpperCase()
  const quoteRead =
    method === 'POST' && ['/schedules/public/quote', '/bookings/quote'].includes(path.split('?')[0])
  const mutation = !['GET', 'HEAD'].includes(method) && !path.startsWith('/auth/') && !quoteRead
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData))
    headers.set('Content-Type', 'application/json')
  const privateFavorite =
    /^\/facilities\/(?:favorites(?:[/?]|$)|[^/?]+\/(?:favorite|operations\/access)(?:[/?]|$))/.test(
      path,
    )
  const publicRequest =
    (path.startsWith('/auth/') && path !== '/auth/logout') ||
    (!privateFavorite && /^\/facilities(?:[/?]|$)/.test(path)) ||
    path === '/sport-categories' ||
    path.startsWith('/schedules/public/') ||
    path.startsWith('/bookings/availability?') ||
    path === '/bookings/quote' ||
    path.startsWith('/bookings/search?')
  if (accessToken && !publicRequest) headers.set('Authorization', `Bearer ${accessToken}`)
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      ...options,
      headers,
      credentials: 'include',
      cache: 'no-store',
    })
  } catch (error) {
    if (
      options.signal?.aborted ||
      (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError')
    )
      throw error
    throw new ApiError(0, 'Không thể kết nối SportHub. Vui lòng thử lại.')
  }
  if (!publicRequest && !path.startsWith('/auth/')) requireAuthGeneration(requestGeneration)
  if (response.status === 401 && retry && !path.startsWith('/auth/')) {
    await refreshSession()
    options.signal?.throwIfAborted()
    if (method === 'GET' || method === 'HEAD') return api<T>(path, options, false)
  }
  if (response.status === 204) {
    if (mutation) invalidateResources(mutationResources(path, method))
    return undefined as T
  }
  const body = await response.json().catch(() => ({}))
  if (!publicRequest && !path.startsWith('/auth/')) requireAuthGeneration(requestGeneration)
  if (!response.ok) {
    const rawMsg = typeof body.message === 'string' ? body.message : 'Không thể thực hiện yêu cầu.'
    // An anonymous session probe is expected on public pages; keep its known 401 off the full error chunk.
    const alreadyVietnamese =
      /[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]/i.test(rawMsg)
    const sessionProbe =
      path === '/auth/refresh' &&
      response.status === 401 &&
      (!Array.isArray(body.fieldErrors) || body.fieldErrors.length === 0) &&
      (alreadyVietnamese ||
        (body.code === 'UNAUTHORIZED' &&
          ['Unauthorized', 'Refresh token is invalid or expired'].includes(rawMsg)))
    const translateError = sessionProbe
      ? () =>
          alreadyVietnamese
            ? rawMsg.replace(/^BLOCKED_RULE:\s*/, '')
            : 'Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại.'
      : (
          await import('./error-translator').catch(() => ({
            translateError: () =>
              'Không thể thực hiện yêu cầu. Vui lòng thử lại hoặc liên hệ cơ sở để được hỗ trợ.',
          }))
        ).translateError
    const translatedMsg = translateError(rawMsg, { code: body.code })
    const translatedFieldErrors = Array.isArray(body.fieldErrors)
      ? body.fieldErrors.map((f: { field: string; message: string }) => ({
          field: f.field,
          message: translateError(f.message) || 'Thông tin không hợp lệ.',
        }))
      : []
    throw new ApiError(
      response.status,
      translatedMsg,
      body.code,
      translatedFieldErrors,
      body.details,
    )
  }
  if (!body || !Object.prototype.hasOwnProperty.call(body, 'data')) {
    if (response.status === 202) {
      if (mutation) invalidateResources(mutationResources(path, method))
      return undefined as T
    }
    throw new ApiError(502, 'Dịch vụ trả dữ liệu không hợp lệ. Vui lòng thử lại.')
  }
  if (mutation) invalidateResources(mutationResources(path, method))
  return (body as Envelope<T>).data
}
export async function privateImage(path: string, signal: AbortSignal): Promise<Blob> {
  if (!/^\/payments\/peer-acquisitions\/[^/?]+\/qr$/.test(path))
    throw new Error('Đường dẫn ảnh riêng tư không hợp lệ.')
  const generation = authGeneration
  const response = await fetch(`/api/v1${path}`, {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    credentials: 'include',
    cache: 'no-store',
    signal,
  })
  requireAuthGeneration(generation)
  if (!response.ok)
    throw new ApiError(
      response.status,
      response.status === 401 || response.status === 403
        ? 'Phiên đăng nhập hoặc quyền xem ảnh đã thay đổi. Vui lòng tải lại.'
        : 'Chưa tải được ảnh. Vui lòng thử lại.',
    )
  if (
    !['image/png', 'image/jpeg'].includes(response.headers.get('Content-Type')?.split(';')[0] ?? '')
  )
    throw new ApiError(502, 'Dịch vụ trả ảnh không hợp lệ.')
  const blob = await response.blob()
  requireAuthGeneration(generation)
  if (blob.size > 5 * 1024 * 1024) throw new ApiError(502, 'Ảnh vượt giới hạn.')
  return blob
}

export function refreshSession(): Promise<Session> {
  if (!refreshFlight) {
    const generation = authGeneration
    refreshFlight = api<Session>('/auth/refresh', { method: 'POST' }, false)
      .then((session) => {
        requireAuthGeneration(generation)
        accept(session)
        return session
      })
      .catch((error) => {
        if (generation === authGeneration && error instanceof ApiError && error.status === 401)
          accept(null)
        throw error
      })
      .finally(() => {
        refreshFlight = null
      })
  }
  return refreshFlight
}
export const authApi = {
  async login(identifier: string, password: string) {
    const generation = ++authGeneration
    accept(null)
    const session = await api<Session>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ identifier, password }),
    })
    requireAuthGeneration(generation)
    accept(session)
    return session
  },
  register: (body: { fullName: string; email?: string; phone?: string; password: string }) =>
    api<RegisterResult>('/auth/register', { method: 'POST', body: JSON.stringify(body) }),
  registerOwner: (body: FormData, key: string) =>
    api<RegisterResult>('/auth/register', {
      method: 'POST',
      body,
      headers: { 'Idempotency-Key': key },
    }),
  verify: (body: { verificationToken?: string; challengeId?: string; code?: string }) =>
    api('/auth/verify', { method: 'POST', body: JSON.stringify(body) }),
  forgot: (identifier: string) =>
    api('/auth/forgot-password', { method: 'POST', body: JSON.stringify({ identifier }) }),
  reset: (body: { challengeId: string; code: string; newPassword: string }) =>
    api('/auth/reset-password', { method: 'POST', body: JSON.stringify(body) }),
  async logout() {
    // Capture the old bearer in fetch before clearing the local session immediately.
    const request = api('/auth/logout', { method: 'POST' }, false)
    authGeneration++
    accept(null)
    await request
  },
}
