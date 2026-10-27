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
  if (!listing)
    return (
      <div className="p-8">
        <p role={read.error ? 'alert' : 'status'}>{read.error || 'Đang tải lượt chuyển nhượng…'}</p>
        <Button variant="outline" onClick={read.reload}>
          Tải lại
        </Button>
      </div>
    )
  const terms = listing.financeTerms
  const peer = terms?.protocol === 'PEER_RESALE_V1'
  return (
    <main className="mx-auto max-w-3xl space-y-6 px-5 py-10">
      <Link className="text-sm text-emerald-700 underline" href="/transfers">
        Chợ chuyển nhượng
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-3xl font-black">{listing.venue.facilityName}</h1>
        <span className="rounded-full bg-emerald-50 px-4 py-2 text-sm text-emerald-700">
          {transferStates[listing.state]}
        </span>
      </div>
      <AdmissionNotice market />
      <AdmissionErrorContext failure={command.failure} />
      <p className="text-slate-500">
        {listing.venue.courtName} · {listing.venue.address}
      </p>
      <section className="space-y-4 rounded-2xl border p-6">
        <p>
          {new Date(listing.startsAt).toLocaleString('vi-VN', { timeZone: listing.venue.timezone })}{' '}
          →{' '}
          {new Date(listing.endsAt).toLocaleTimeString('vi-VN', {
            timeZone: listing.venue.timezone,
          })}
        </p>
        <div className="flex items-baseline gap-4">
          <strong className="text-3xl text-emerald-700">
            {money(listing.price, listing.currency)}
          </strong>
          <span className="text-slate-400 line-through">
            {money(listing.originalAmount, listing.currency)}
          </span>
        </div>
        <div className="flex flex-wrap gap-2">
          {listing.venue.amenities.map((item) => (
            <span key={item} className="rounded-full bg-slate-100 px-3 py-1 text-sm">
              {item}
            </span>
          ))}
        </div>
        <p className="text-sm text-amber-800">
          Hạn nhận:{' '}
          {new Date(listing.deadline).toLocaleString('vi-VN', { timeZone: listing.venue.timezone })}
        </p>
      </section>
      {terms ? (
        <TransferFinanceNotice terms={terms} />
      ) : (
        <p className="text-sm text-amber-800">
          Người bán cần cập nhật điều khoản trước khi có giao dịch mới.
        </p>
      )}
      {peer && (
        <p className="text-sm">
          Bấm mua tạo phòng trao đổi riêng và lượt giữ tối đa 5 phút. Cần gửi ảnh cùng mã giao dịch
          trong hạn. Người bán xác nhận đã nhận đủ tiền mới bàn giao sân.
        </p>
      )}
      {!listing.own && terms && peer && (
        <label className="flex items-start gap-3 text-sm">
          <input
            type="checkbox"
            checked={acceptedHash === terms.termsHash}
            onChange={(event) => setAcceptedHash(event.target.checked ? terms.termsHash : '')}
            disabled={command.busy || command.blocked || blocked}
            className="mt-1"
          />
          Tôi đồng ý trả trực tiếp người bán, gửi chứng từ, nhận quyền sau khi xác nhận hoặc Owner
          phân xử; không bán lại lượt đã nhận.
        </label>
      )}
      {(command.error || read.error) && (
        <p role="alert" className="text-red-700">
          {command.error || read.error}
        </p>
      )}
      <div className="flex flex-wrap gap-3">
        {listing.own ? (
          <>
            {listing.state === 'ACTIVE' && (
              <>
                {peer && (
                  <Link
                    href={`/customer/transfers/${id}/edit`}
                    className="rounded-full bg-emerald-600 px-5 py-3 text-sm text-white"
                  >
                    Sửa tin
                  </Link>
                )}
                <Button
                  variant="outline"
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
            <Link href="/customer/transfers" className="p-3 text-sm text-emerald-700">
              Quản lý tin và phiên trao đổi
            </Link>
          </>
        ) : (
          <Button
            className="h-12 bg-emerald-600 px-6 text-white"
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
                (acquisition) => router.push(`/customer/transfers/acquisitions/${acquisition.id}`),
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
    </main>
  )
}
