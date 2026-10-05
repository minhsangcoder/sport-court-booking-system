export type Role = 'CUSTOMER' | 'OWNER' | 'STAFF' | 'ADMIN'
export type UserProfile = {
  id: string; fullName: string; email: string | null; phone: string | null;
  roles: Role[]; status: string; emailVerified: boolean; phoneVerified: boolean;
  avatarUrl?: string | null; dateOfBirth?: string | null;
}
export type Session = { accessToken: string; tokenType: string; expiresIn: number; user: UserProfile }
export type RegisterResult = { userId: string; accountStatus: string; verificationChallengeId: string; verificationExpiresAt: string }
type Envelope<T> = { data: T; message: string }
export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: string,
    public fieldErrors: { field: string; message: string }[] = []) { super(message) }
}
// Temporary typed adapter for the checked-in OpenAPI contracts. Tokens stay in memory.
let accessToken: string | null = null
let refreshFlight: Promise<Session> | null = null
function accept(session: Session | null) {
  accessToken = session?.accessToken ?? null
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('sporthub-session', { detail: session?.user ?? null }))
}
export async function api<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const publicRequest=(path.startsWith('/auth/')&&path!=='/auth/logout')||/^\/facilities(?:[/?]|$)/.test(path)||path==='/sport-categories'||path.startsWith('/schedules/public/')||path.startsWith('/bookings/availability?')
  if (accessToken&&!publicRequest) headers.set('Authorization', `Bearer ${accessToken}`)
  let response: Response
  try { response = await fetch(`/api/v1${path}`, { ...options, headers, credentials: 'include', cache: 'no-store' }) }
  catch { throw new ApiError(0, 'Không thể kết nối SportHub. Vui lòng thử lại.') }
  if (response.status === 401 && retry && !path.startsWith('/auth/')) {
    await refreshSession()
    return api<T>(path, options, false)
  }
  if (response.status === 204) return undefined as T
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new ApiError(response.status, body.message ?? 'Không thể thực hiện yêu cầu.', body.code, body.fieldErrors)
  return (body as Envelope<T>).data
}
export function refreshSession(): Promise<Session> {
  if (!refreshFlight) refreshFlight = api<Session>('/auth/refresh', { method: 'POST' }, false)
    .then(session => { accept(session); return session })
    .catch(error => { if (error instanceof ApiError && error.status === 401) accept(null); throw error })
    .finally(() => { refreshFlight = null })
  return refreshFlight
}
export const authApi = {
  async login(identifier: string, password: string) {
    const session = await api<Session>('/auth/login', { method: 'POST', body: JSON.stringify({ identifier, password }) })
    accept(session); return session
  },
  register: (body: { fullName: string; email?: string; phone?: string; password: string }) =>
    api<RegisterResult>('/auth/register', { method: 'POST', body: JSON.stringify(body) }),
  verify: (body: { verificationToken?: string; challengeId?: string; code?: string }) =>
    api('/auth/verify', { method: 'POST', body: JSON.stringify(body) }),
  forgot: (identifier: string) => api('/auth/forgot-password', { method: 'POST', body: JSON.stringify({ identifier }) }),
  reset: (body: { challengeId: string; code: string; newPassword: string }) =>
    api('/auth/reset-password', { method: 'POST', body: JSON.stringify(body) }),
  async logout() { try { await api('/auth/logout', { method: 'POST' }, false) } finally { accept(null) } },
}
