'use client'
import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import { usePolling } from '@/lib/use-polling'
import { RequireAuth } from '@/features/auth/require-auth'
import { Button } from '@/components/ui/button'
import { money } from '@/features/schedule/types'
import { useRemaining } from '@/features/booking/countdown'
import { type Listing, type Acquisition, transferStates } from './types'
import { TransferFinanceNotice } from './finance-notice'
import { usePeerCommand } from './use-peer-command'
import { AdmissionNotice, AdmissionErrorContext } from '@/features/booking/admission-notice'
import { PageBanner } from '@/components/page-banner'
import { CourtLines } from '@/features/home/home-art'
import { CalendarClock, Clock, Info, MessagesSquare, PencilLine } from 'lucide-react'
import {
  amberNotice,
  bannerLink,
  card,
  outlinePill,
  primaryPill,
  statePill,
} from './transfer-styles'

export function ListingDetail({ id }: { id: string }) {
  const { user } = useAuth()
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Loaded key={`${user?.id}:${id}`} id={id} />
    </RequireAuth>
  )
}
function Loaded({ id }: { id: string }) {
  const router = useRouter()
  const read = useApi<Listing>(`/transfers/${id}`)
  const command = usePeerCommand()
  const [acceptedHash, setAcceptedHash] = useState('')
  const remaining = useRemaining(read.data?.deadline)
  const blocked = Boolean(read.failure && [401, 403].includes(read.failure.status))
  usePolling(read.poll, {
    enabled: Boolean(read.data && !blocked),
    scope: id,
    interval: 10000,
    deadline: read.data ? Date.parse(read.data.deadline) : undefined,
  })
  const listing = read.data
  // The seller reaches each buyer's private conversation from here.
  const sessions = useApi<Acquisition[]>(listing?.own ? '/transfers/acquisitions' : null)
  if (!listing)
    return (
      <div className="mx-auto max-w-3xl space-y-4 px-4 py-10 sm:px-6">
        <p role={read.error ? 'alert' : 'status'}>{read.error || 'Đang tải lượt chuyển nhượng…'}</p>
        <Button variant="outline" onClick={read.reload} className={outlinePill}>
          Tải lại
        </Button>
      </div>
    )
  const terms = listing.financeTerms
  const peer = terms?.protocol === 'PEER_RESALE_V1'
  const listingSessions = sessions.data?.filter((a) => a.listingId === id) ?? []
  return (
    <main>
      <PageBanner
        icon="pickleball"
        eyebrow={
          <Link className={bannerLink} href="/transfers">
            Chợ chuyển nhượng
          </Link>
        }
        title={listing.venue.facilityName}
        description={`${listing.venue.courtName} · ${listing.venue.address}`}
        actions={<span className={statePill}>{transferStates[listing.state]}</span>}
      />
      <div className="home-rise mx-auto max-w-3xl space-y-6 px-4 py-7 sm:px-6 sm:py-9">
        <AdmissionNotice market />
        <AdmissionErrorContext failure={command.failure} />
        <section className={`${card} space-y-4 p-5 sm:p-7`}>
          <CourtLines className="pointer-events-none absolute -right-20 -top-12 -z-10 w-80 -rotate-6 text-emerald-600/[0.05]" />
          <p className="inline-flex flex-wrap items-center gap-2 rounded-full bg-emerald-50 px-3.5 py-1.5 text-sm font-semibold text-emerald-800 dark:bg-emerald-500/10 dark:text-emerald-300">
            <CalendarClock className="size-4 shrink-0" />
            {new Date(listing.startsAt).toLocaleString('vi-VN', {
              timeZone: listing.venue.timezone,
            })}{' '}
            →{' '}
            {new Date(listing.endsAt).toLocaleTimeString('vi-VN', {
              timeZone: listing.venue.timezone,
            })}
          </p>
          <div className="flex items-baseline gap-4">
            <strong className="text-4xl font-extrabold tracking-tight text-emerald-700 dark:text-emerald-400">
              {money(listing.price, listing.currency)}
            </strong>
            <span className="text-slate-400 line-through">
              {money(listing.originalAmount, listing.currency)}
            </span>
          </div>
          <div className="flex flex-wrap gap-2">
            {listing.venue.amenities.map((item) => (
              <span
                key={item}
                className="rounded-full border border-slate-200/80 bg-slate-50 px-3 py-1 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300"
              >
                {item}
              </span>
            ))}
          </div>
          <p className="inline-flex items-center gap-2 text-sm font-semibold text-amber-800 dark:text-amber-300">
            <Clock className="size-4" />
            Hạn nhận:{' '}
            {new Date(listing.deadline).toLocaleString('vi-VN', {
              timeZone: listing.venue.timezone,
            })}
          </p>
        </section>
        {terms ? (
          <TransferFinanceNotice terms={terms} />
        ) : (
          <p className={amberNotice}>
            Người bán cần cập nhật điều khoản trước khi có giao dịch mới.
          </p>
        )}
        {peer && (
          <p className="flex items-start gap-2.5 text-sm leading-relaxed text-slate-600 dark:text-slate-300">
            <Info className="mt-0.5 size-4 shrink-0 text-slate-400" />
            Bấm mua tạo phòng trao đổi riêng và lượt giữ tối đa 5 phút. Cần gửi ảnh cùng mã giao
            dịch trong hạn. Người bán xác nhận đã nhận đủ tiền mới bàn giao sân.
          </p>
        )}
        {listing.own && listingSessions.length > 0 && (
          <section className={`${card} space-y-3 p-5 sm:p-6`}>
            <h2 className="flex items-center gap-2 text-lg font-extrabold tracking-tight text-slate-950 dark:text-white">
              <MessagesSquare className="size-5 text-emerald-600" />
              Phiên trao đổi với người mua
            </h2>
            <ul className="space-y-2.5">
              {listingSessions.map((a) => (
                <li
                  key={a.id}
                  className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200/80 bg-slate-50/80 p-3.5 text-sm dark:border-slate-800 dark:bg-slate-800/50"
                >
                  <span className="text-slate-700 dark:text-slate-300">
                    Giao dịch {a.id.slice(0, 8).toUpperCase()} ·{' '}
                    {transferStates[a.state] ?? a.state}
                  </span>
                  <Link href={`/customer/transfers/acquisitions/${a.id}`} className={outlinePill}>
                    <MessagesSquare className="size-4" />
                    Mở trao đổi với người mua
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        )}
        {!listing.own && terms && peer && (
          <label className="flex items-start gap-3 text-sm text-slate-700 dark:text-slate-300">
            <input
              type="checkbox"
              checked={acceptedHash === terms.termsHash}
              onChange={(event) => setAcceptedHash(event.target.checked ? terms.termsHash : '')}
              disabled={command.busy || command.blocked || blocked}
              className="mt-1 size-4 accent-emerald-600"
            />
            Tôi đồng ý chuyển tiền trực tiếp cho người bán. Sân chỉ thuộc về tôi khi người bán xác nhận
            đã nhận tiền; mọi trường hợp khác sân vẫn thuộc người bán và hai bên tự giải quyết qua chủ
            sân. Không bán lại lượt đã nhận.
          </label>
        )}
        {(command.error || read.error) && (
          <p
            role="alert"
            className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          >
            {command.error || read.error}
          </p>
        )}
        <div className="flex flex-wrap items-center gap-3">
          {listing.own ? (
            <>
              {listing.state === 'ACTIVE' && (
                <>
                  {peer && (
                    <Link href={`/customer/transfers/${id}/edit`} className={primaryPill}>
                      <PencilLine className="size-4" />
                      Sửa tin
                    </Link>
                  )}
                  <Button
                    variant="outline"
                    className={outlinePill}
                    disabled={command.busy || command.blocked || blocked}
                    onClick={() => {
                      if (
                        window.confirm(
                          'Rút tin chưa có khai chuyển tiền? Lượt đặt gốc vẫn giữ nguyên.',
                        )
                      )
                        void command.run(
                          `/transfers/${id}/withdraw`,
                          peer ? { version: listing.version } : {},
                          read.reload,
                        )
                    }}
                  >
                    Rút tin
                  </Button>
                </>
              )}
              <Link
                href="/customer/transfers"
                className="px-2 text-sm font-semibold text-emerald-700 hover:underline dark:text-emerald-400"
              >
                Quản lý tin và phiên trao đổi
              </Link>
            </>
          ) : (
            <Button
              className={primaryPill}
              disabled={
                command.busy ||
                command.blocked ||
                blocked ||
                !peer ||
                !listing.available ||
                remaining === 0 ||
                !terms ||
                acceptedHash !== terms.termsHash
              }
              onClick={() =>
                peer &&
                void command.run<Acquisition>(
                  `/transfers/${id}/acquisitions`,
                  { acceptedTermsHash: acceptedHash },
                  (acquisition) =>
                    router.push(`/customer/transfers/acquisitions/${acquisition.id}`),
                )
              }
            >
              {command.busy
                ? 'Đang giữ giao dịch…'
                : !peer
                  ? 'Tin cũ chỉ dùng để xem lịch sử'
                  : listing.available
                    ? 'Mua lượt đặt này'
                    : 'Lượt đặt hiện không khả dụng'}
            </Button>
          )}
        </div>
      </div>
    </main>
  )
}
