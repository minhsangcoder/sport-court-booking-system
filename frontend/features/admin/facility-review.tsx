'use client'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { BannerPage } from '@/components/page-banner'
import { RecordDetails } from '@/components/record-details'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RequireAuth } from '@/features/auth/require-auth'
import { type ReviewDocument } from '@/features/facility/document-page'
import { type FacilityReview, reviewLabels } from '@/features/facility/review-page'
import { type Court, type Facility, type Media } from '@/features/facility/types'
import { api } from '@/lib/api'
import { displayLabel, timezoneLabel } from '@/lib/display-labels'
import { useApi } from '@/lib/use-api'
import Link from 'next/link'
import { useState } from 'react'
import { AdminPage } from './admin-nav'
import { FacilityStatus } from './facility-status'
import { facilityCode } from './facility-picker'
import {
  ArrowRight,
  Clock,
  FileText,
  History,
  Images,
  LayoutGrid,
  MapPin,
  Phone,
  ShieldCheck,
  TriangleAlert,
  UserRound,
} from 'lucide-react'
export function AdminFacilities() {
  return (
    <RequireAuth roles={['ADMIN']}>
      <List />
    </RequireAuth>
  )
}
function List() {
  const [query, setQuery] = useState('status=PENDING_APPROVAL')
  const r = useApi<Facility[]>(`/admin/facilities?${query}`)
  return (
    <AdminPage active="facilities" title="Kiểm duyệt cơ sở">
      <form
        className="mt-6 flex flex-wrap items-end gap-4 p-5 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise"
        onSubmit={(e) => {
          e.preventDefault()
          const f = new FormData(e.currentTarget),
            p = new URLSearchParams()
          for (const key of ['q', 'status']) {
            const v = String(f.get(key) || '')
            if (v) p.set(key, v)
          }
          setQuery(p.toString())
        }}
      >
        <label className="grid gap-2 text-sm">
          Tên hoặc địa chỉ
          <Input name="q" placeholder="Tên, liên hệ, địa chỉ" />
        </label>
        <label className="grid gap-2 text-sm">
          Trạng thái
          <select
            name="status"
            defaultValue="PENDING_APPROVAL"
            className="h-11 rounded-xl border bg-white px-3.5"
          >
            <option value="">Tất cả</option>
            {['DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'REJECTED', 'SUSPENDED'].map((state) => (
              <option key={state} value={state}>
                {reviewLabels[state]}
              </option>
            ))}
          </select>
        </label>
        <Button type="submit" className="bg-emerald-600 text-white">
          Tra cứu
        </Button>
      </form>
      {r.loading && (
        <p role="status" className="mt-6">
          Đang tải hồ sơ cơ sở…
        </p>
      )}
      {r.error && (
        <p role="alert" className="mt-6 text-red-700">
          {r.error}
          <button className="ml-4 underline" onClick={r.reload}>
            Thử lại
          </button>
        </p>
      )}
      {r.data?.length === 0 && (
        <p className="mt-6 p-6 text-slate-500 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          Không có cơ sở phù hợp.
        </p>
      )}
      <div className="mt-6 divide-y rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
        {r.data?.map((f) => (
          <Link
            href={`/admin/facilities/${f.id}`}
            key={f.id}
            className="flex flex-wrap justify-between gap-4 p-6 hover:bg-emerald-50"
          >
            <div>
              <h2 className="font-bold">{f.name}</h2>
              <p className="mt-2 text-sm text-slate-500">
                {f.addressLine}, {f.ward}, {f.province}
              </p>
            </div>
            <span className="text-sm font-semibold text-emerald-700">
              {reviewLabels[f.status] || displayLabel(f.status)}
            </span>
          </Link>
        ))}
      </div>
    </AdminPage>
  )
}
type ReviewDetail = {
  facility: Facility
  courts: Court[]
  reviews: FacilityReview[]
  audit: { id: string; action: string; occurred_at: string; details: string | null }[]
  possibleDuplicates: Facility[]
  /** Set for a first facility: it is decided through its Owner application. */
  applicationId?: string | null
}
const CARD =
  'rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise'
const EYEBROW = 'text-[11px] font-semibold uppercase tracking-[0.16em] text-emerald-700'
const statusTone = (status: string) =>
  status === 'ACTIVE'
    ? 'bg-emerald-400/15 text-emerald-200 ring-emerald-300/40'
    : status === 'PENDING_APPROVAL'
      ? 'bg-amber-400/15 text-amber-200 ring-amber-300/40'
      : status === 'REJECTED' || status === 'SUSPENDED'
        ? 'bg-red-400/15 text-red-200 ring-red-300/40'
        : 'bg-white/10 text-slate-200 ring-white/20'
// Audit of features removed from the product (Owner refund policy, facility transfer fee).
// The rows stay as append-only history but are not part of today's review timeline.
const RETIRED_AUDIT = new Set(['REFUND_POLICY_UPDATED', 'TRANSFER_FEE_POLICY_UPDATED'])
const decisionTitles: Record<string, string> = {
  APPROVE: 'Phê duyệt và công khai cơ sở',
  SUPPLEMENT_REQUIRED: 'Yêu cầu chủ cơ sở bổ sung hồ sơ',
  REJECT: 'Từ chối hồ sơ',
}
export function AdminFacilityReview({ id }: { id: string }) {
  return (
    <RequireAuth roles={['ADMIN']}>
      <Detail id={id} />
    </RequireAuth>
  )
}
function Detail({ id }: { id: string }) {
  const r = useApi<ReviewDetail>(`/admin/facilities/${id}`),
    docs = useApi<ReviewDocument[]>(`/admin/facilities/${id}/documents`),
    images = useApi<Media[]>(`/admin/facilities/${id}/images`)
  const [tab, setTab] = useState('profile')
  const [action, setAction] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function decide(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!action || busy) return
    const f = new FormData(e.currentTarget)
    setBusy(true)
    setError('')
    try {
      await api(`/admin/facilities/${id}/decision`, {
        method: 'POST',
        body: JSON.stringify({ action, reason: f.get('reason') }),
      })
      setAction(null)
      r.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể xử lý hồ sơ.')
    } finally {
      setBusy(false)
    }
  }
  if (r.loading)
    return (
      <p role="status" className="p-8">
        Đang tải hồ sơ thẩm định…
      </p>
    )
  if (!r.data)
    return (
      <p role="alert" className="p-8 text-red-700">
        {r.error}
        <button className="ml-3 underline" onClick={r.reload}>
          Thử lại
        </button>
      </p>
    )
  const d = r.data
  const f = d.facility
  const statusText = reviewLabels[f.status] || displayLabel(f.status)
  const tabs: [string, string, number | null][] = [
    ['profile', 'Thông tin và sân', d.courts.length],
    ['documents', 'Tài liệu và hình ảnh', (docs.data?.length ?? 0) + (images.data?.length ?? 0)],
    ['history', 'Lịch sử kiểm duyệt', d.reviews.length],
  ]
  return (
    <BannerPage
      eyebrow={
        <Link className={bannerLink} href="/admin/facilities">
          Kiểm duyệt cơ sở
        </Link>
      }
      title={f.name ?? ''}
      description={[f.addressLine, f.ward, f.province].filter(Boolean).join(', ')}
      actions={
        <span
          className={`rounded-full px-3.5 py-1.5 text-xs font-semibold ring-1 ${statusTone(f.status)}`}
        >
          {statusText}
        </span>
      }
      width="max-w-5xl"
    >
      <div className="grid gap-4 sm:grid-cols-3">
        {[
          ['Mã cơ sở', facilityCode(f.id), <ShieldCheck key="i" size={18} />],
          ['Số sân', `${d.courts.length} sân`, <LayoutGrid key="i" size={18} />],
          ['Lần gửi duyệt', `${d.reviews.length} lần`, <History key="i" size={18} />],
        ].map(([label, value, icon]) => (
          <div key={String(label)} className={`flex items-center gap-4 p-5 ${CARD}`}>
            <div className="flex size-10 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-400">
              {icon}
            </div>
            <div>
              <p className="text-xs font-bold uppercase tracking-wider text-slate-500">{label}</p>
              <p className="mt-1 text-lg font-extrabold tracking-tight">{value}</p>
            </div>
          </div>
        ))}
      </div>
      {d.possibleDuplicates.length > 0 && (
        <section className="mt-6 flex gap-4 rounded-3xl border border-amber-200 bg-amber-50 p-5 text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
          <TriangleAlert size={20} className="mt-0.5 shrink-0" />
          <div>
            <h2 className="font-bold">Có địa chỉ trùng với hồ sơ khác</h2>
            <div className="mt-3 flex flex-wrap gap-2">
              {d.possibleDuplicates.map((other) => (
                <Link
                  key={other.id}
                  href={`/admin/facilities/${other.id}`}
                  className="rounded-full bg-white px-3.5 py-1.5 text-sm font-semibold ring-1 ring-amber-200 hover:ring-amber-400 dark:bg-slate-900 dark:ring-amber-800"
                >
                  {other.name} · {reviewLabels[other.status] || displayLabel(other.status)}
                </Link>
              ))}
            </div>
          </div>
        </section>
      )}
      <div role="tablist" aria-label="Hồ sơ thẩm định cơ sở" className="mt-8 flex flex-wrap gap-2">
        {tabs.map(([value, label, count]) => (
          <button
            role="tab"
            aria-selected={tab === value}
            onClick={() => setTab(value)}
            key={value}
            className={`whitespace-nowrap rounded-full px-4 py-2 text-sm font-semibold transition-colors ${
              tab === value
                ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
                : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:ring-emerald-400 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700'
            }`}
          >
            {label}
            {count != null && <span className="ml-2 text-xs opacity-70">{count}</span>}
          </button>
        ))}
      </div>
      {tab === 'profile' && (
        <div className="mt-6 grid gap-5 lg:grid-cols-5">
          <section className={`p-6 lg:col-span-3 ${CARD}`}>
            <p className={EYEBROW}>Thông tin cơ sở</p>
            <dl className="mt-4 space-y-4 text-sm">
              <div className="flex gap-3">
                <MapPin size={18} className="mt-0.5 shrink-0 text-slate-400" />
                <div>
                  <dt className="text-slate-500">Địa chỉ</dt>
                  <dd className="mt-0.5 font-medium">
                    {f.addressLine}, {f.ward}, {f.province}
                  </dd>
                </div>
              </div>
              <div className="flex gap-3">
                <Phone size={18} className="mt-0.5 shrink-0 text-slate-400" />
                <div>
                  <dt className="text-slate-500">Liên hệ</dt>
                  <dd className="mt-0.5 font-medium">{f.phone}</dd>
                </div>
              </div>
              <div className="flex gap-3">
                <Clock size={18} className="mt-0.5 shrink-0 text-slate-400" />
                <div>
                  <dt className="text-slate-500">Múi giờ</dt>
                  <dd className="mt-0.5 font-medium">{timezoneLabel(f.timezone)}</dd>
                </div>
              </div>
            </dl>
            {f.description && (
              <p className="mt-5 rounded-2xl bg-slate-50 p-4 text-sm leading-relaxed dark:bg-slate-800">
                {f.description}
              </p>
            )}
            <p className="mt-5 text-sm text-slate-500">Tiện ích</p>
            <div className="mt-2 flex flex-wrap gap-2">
              {f.amenities.length === 0 && <span className="text-sm">Chưa khai báo</span>}
              {f.amenities.map((a) => (
                <span
                  key={a}
                  className="rounded-full bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300"
                >
                  {a}
                </span>
              ))}
            </div>
            <Link
              href={`/admin/accounts/${f.ownerId}`}
              className="mt-6 inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-semibold text-emerald-700 ring-1 ring-emerald-200 hover:bg-emerald-50 dark:ring-emerald-800 dark:hover:bg-emerald-950"
            >
              <UserRound size={16} /> Xem tài khoản chủ cơ sở <ArrowRight size={14} />
            </Link>
          </section>
          <section className={`p-6 lg:col-span-2 ${CARD}`}>
            <p className={EYEBROW}>{d.courts.length} sân</p>
            <ul className="mt-4 space-y-2">
              {d.courts.length === 0 && <li className="text-sm text-slate-500">Chưa có sân.</li>}
              {d.courts.map((c) => (
                <li key={c.id} className="rounded-2xl bg-slate-50 p-4 dark:bg-slate-800">
                  <div className="flex items-center justify-between gap-3">
                    <p className="font-semibold">{c.name}</p>
                    <span
                      className={`rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${
                        c.enabled
                          ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-200'
                          : 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
                      }`}
                    >
                      {c.enabled ? 'Đang bật' : 'Tạm tắt'}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-slate-500">
                    Mã sân {c.code}
                    {c.description && ` · ${c.description}`}
                  </p>
                </li>
              ))}
            </ul>
          </section>
        </div>
      )}
      {tab === 'documents' && (
        <section className="mt-6 space-y-6">
          {(docs.loading || images.loading) && <p role="status">Đang tải tài liệu…</p>}
          {(docs.error || images.error) && (
            <p role="alert" className="text-red-700">
              {docs.error || images.error}
              <button
                className="ml-4 underline"
                onClick={() => {
                  docs.reload()
                  images.reload()
                }}
              >
                Thử lại
              </button>
            </p>
          )}
          <div className={`p-6 ${CARD}`}>
            <p className={EYEBROW}>Tài liệu riêng tư</p>
            {docs.data?.length === 0 && (
              <p className="mt-4 text-sm text-amber-800">Chưa có tài liệu địa điểm đính kèm.</p>
            )}
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              {docs.data?.map((doc) => (
                <a
                  key={doc.id}
                  href={doc.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-center gap-3 rounded-2xl border border-slate-200 p-4 text-sm transition hover:border-emerald-400 hover:bg-emerald-50 dark:border-slate-700 dark:hover:bg-emerald-950"
                >
                  <FileText size={20} className="shrink-0 text-emerald-600" />
                  <span className="min-w-0">
                    <span className="block truncate font-semibold">{doc.name}</span>
                    <span className="text-xs text-slate-500">
                      {Math.ceil(doc.sizeBytes / 1024)} KB
                      {doc.archived && ' · Bản lưu của hồ sơ trước'}
                    </span>
                  </span>
                </a>
              ))}
            </div>
          </div>
          <div className={`p-6 ${CARD}`}>
            <p className={EYEBROW}>Hình ảnh cơ sở</p>
            {images.data?.length === 0 && (
              <p className="mt-4 text-sm text-slate-500">Chưa có hình ảnh.</p>
            )}
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              {images.data?.map((image, i) => (
                <a
                  href={image.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  key={image.id}
                  className="flex items-center gap-3 rounded-2xl border border-slate-200 p-4 text-sm transition hover:border-emerald-400 hover:bg-emerald-50 dark:border-slate-700 dark:hover:bg-emerald-950"
                >
                  <Images size={20} className="shrink-0 text-emerald-600" />
                  <span>
                    <span className="block font-semibold">Ảnh {i + 1}</span>
                    <span className="text-xs text-slate-500">
                      {Math.ceil(image.sizeBytes / 1024)} KB
                    </span>
                  </span>
                </a>
              ))}
            </div>
          </div>
          <p className="text-xs text-slate-500">
            Việc xem tài liệu được ghi vào audit. Tải lại để lấy liên kết xem mới.
          </p>
        </section>
      )}
      {tab === 'history' && (
        <section className="mt-6 space-y-6">
          <div className="space-y-4">
            {d.reviews.length === 0 && (
              <p className={`p-6 text-sm text-slate-500 ${CARD}`}>Chưa có lần gửi duyệt nào.</p>
            )}
            {d.reviews.map((review) => (
              <article key={review.id} className={`p-6 ${CARD}`}>
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <p className="font-bold">
                    {reviewLabels[review.state] || displayLabel(review.state)}
                  </p>
                  <p className="text-xs text-slate-500">
                    Nộp {new Date(review.submittedAt).toLocaleString('vi-VN')}
                  </p>
                </div>
                {review.reason && (
                  <p className="mt-3 rounded-2xl bg-slate-50 p-4 text-sm dark:bg-slate-800">
                    {review.reason}
                  </p>
                )}
                <details className="mt-4 text-sm">
                  <summary className="cursor-pointer font-semibold text-emerald-700">
                    Hồ sơ tại lúc gửi duyệt
                  </summary>
                  <div className="mt-3 overflow-x-auto rounded-2xl bg-slate-50 p-4 text-xs dark:bg-slate-800">
                    <RecordDetails value={review.snapshot} />
                  </div>
                </details>
              </article>
            ))}
          </div>
          <div className={`p-6 ${CARD}`}>
            <p className={EYEBROW}>Nhật ký thẩm định</p>
            <ol className="mt-5 space-y-5 border-l-2 border-slate-100 pl-5 dark:border-slate-800">
              {d.audit
                .filter((a) => !RETIRED_AUDIT.has(a.action))
                .map((a) => (
                  <li key={a.id} className="relative text-sm">
                    <span className="absolute -left-[27px] top-1 size-3 rounded-full bg-emerald-500 ring-4 ring-white dark:ring-slate-900" />
                    <p className="font-semibold">{displayLabel(a.action)}</p>
                    <p className="mt-0.5 text-xs text-slate-500">
                      {new Date(a.occurred_at).toLocaleString('vi-VN')}
                    </p>
                    {a.details && (
                      <div className="mt-2 break-all text-xs text-slate-500">
                        <RecordDetails value={a.details} />
                      </div>
                    )}
                  </li>
                ))}
            </ol>
          </div>
        </section>
      )}
      {f.status === 'PENDING_APPROVAL' && d.applicationId && (
        <section className={`mt-8 flex flex-wrap items-center justify-between gap-4 p-6 ${CARD}`}>
          <div>
            <h2 className="text-lg font-extrabold tracking-tight">Quyết định kiểm duyệt</h2>
            <p className="mt-1 text-sm text-slate-500">
              Đây là cơ sở đầu tiên của một hồ sơ đăng ký chủ cơ sở. Phê duyệt, yêu cầu bổ sung hoặc
              từ chối được thực hiện tại hồ sơ đăng ký đó.
            </p>
          </div>
          <Link
            href={`/admin/owner-applications/${d.applicationId}`}
            className="inline-flex items-center gap-2 rounded-full bg-emerald-600 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-emerald-600/20 hover:bg-emerald-700"
          >
            Mở hồ sơ đăng ký <ArrowRight size={16} />
          </Link>
        </section>
      )}
      {f.status === 'PENDING_APPROVAL' && !d.applicationId && (
        <section className={`mt-8 p-6 ${CARD}`}>
          <h2 className="text-lg font-extrabold tracking-tight">Quyết định kiểm duyệt</h2>
          <div className="mt-5 flex flex-wrap gap-3">
            <Button onClick={() => setAction('APPROVE')} className="bg-emerald-600 text-white">
              Phê duyệt cơ sở
            </Button>
            <Button variant="outline" onClick={() => setAction('SUPPLEMENT_REQUIRED')}>
              Yêu cầu bổ sung
            </Button>
            <Button variant="outline" onClick={() => setAction('REJECT')}>
              Từ chối hồ sơ
            </Button>
          </div>
          {action && (
            <form
              role="dialog"
              aria-label="Xác nhận quyết định kiểm duyệt"
              onSubmit={decide}
              className="mt-6 space-y-4 rounded-2xl bg-slate-50 p-5 dark:bg-slate-800"
            >
              <h3 className="font-bold">{decisionTitles[action]}</h3>
              <label className="grid gap-2 text-sm">
                Ghi chú và hướng dẫn
                <textarea
                  name="reason"
                  className="min-h-28 rounded-2xl border border-slate-200/90 bg-white p-3 dark:border-slate-700 dark:bg-slate-900"
                  required
                  minLength={action === 'APPROVE' ? 2 : 20}
                  maxLength={2000}
                  disabled={busy}
                />
              </label>
              {error && (
                <p role="alert" className="text-red-700">
                  {error}
                </p>
              )}
              <div className="flex gap-3">
                <Button type="submit" disabled={busy} className="bg-emerald-600 text-white">
                  {busy ? 'Đang xử lý…' : 'Xác nhận quyết định'}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setAction(null)}
                  disabled={busy}
                >
                  Quay lại
                </Button>
              </div>
            </form>
          )}
        </section>
      )}
      <div className="mt-8">
        <FacilityStatus id={id} status={f.status} />
      </div>
    </BannerPage>
  )
}
