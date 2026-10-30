'use client'
import dynamic from 'next/dynamic'
import { useState } from 'react'
import Link from 'next/link'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import { usePolling } from '@/lib/use-polling'
import { Countdown, useRemaining } from '@/features/booking/countdown'
import { Button } from '@/components/ui/button'
import { PeerPayment } from './peer-payment'
import { finalPeerPhase, peerPhases, type PeerAttempt } from './peer-types'
import { PageBanner } from '@/components/page-banner'
import { ArrowLeftRight, MessagesSquare, RefreshCw } from 'lucide-react'
import {
  amberNotice,
  bannerLink,
  card,
  emeraldNotice,
  outlinePill,
  primaryPill,
  statePill,
} from './transfer-styles'

const Chat = dynamic(() => import('./peer-chat').then((module) => module.PeerChat), {
  loading: () => <p role="status">Đang mở phòng trao đổi…</p>,
})
export default function PeerAcquisitionPage({ id }: { id: string }) {
  const { user } = useAuth()
  return user ? <Loaded key={`${user.id}:${id}`} id={id} userId={user.id} /> : null
}
function Loaded({ id, userId }: { id: string; userId: string }) {
  const read = useApi<PeerAttempt>(`/transfers/acquisitions/${id}/peer`)
  const [showChat, setShowChat] = useState(true)
  const [until] = useState(() => Date.now() + 30 * 60_000)
  const [timedOut, setTimedOut] = useState(false)
  const remaining = useRemaining(read.data?.payBy)
  const blocked = Boolean(read.failure && [401, 403].includes(read.failure.status))
  usePolling(read.poll, {
    enabled: Boolean(read.data && !finalPeerPhase(read.data.phase) && !blocked && !timedOut),
    scope: `${userId}:${id}`,
    interval: 4000,
    deadline: until,
    onTimeout: () => setTimedOut(true),
  })
  const a = read.data
  return (
    <main>
      <PageBanner
        icon="shuttle"
        eyebrow={
          <Link href="/customer/transfers" className={bannerLink}>
            Giao dịch & trao đổi
          </Link>
        }
        title="Chuyển nhượng trực tiếp giữa hai khách"
        actions={
          a && (
            <span className={statePill}>
              {userId === a.sellerId ? 'Người bán (A)' : 'Người mua (B)'}
            </span>
          )
        }
      />
      <div className="home-rise mx-auto max-w-3xl space-y-5 px-4 py-7 sm:px-6 sm:py-9">
        {read.loading && !a && <p role="status">Đang đối chiếu quyền sân…</p>}
        {read.error && (
          <p
            role="alert"
            className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          >
            {read.error}
          </p>
        )}
        {a && (
          <>
            <p role="status" className={`${emeraldNotice} flex items-center gap-2.5`}>
              <span className="relative flex size-2.5">
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-500 opacity-60" />
                <span className="relative inline-flex size-2.5 rounded-full bg-emerald-500" />
              </span>
              {peerPhases[a.phase] ?? 'Đang đối chiếu trạng thái'}
            </p>
            <p className="flex items-start gap-2.5 text-sm leading-relaxed text-slate-600 dark:text-slate-300">
              <ArrowLeftRight className="mt-0.5 size-4 shrink-0 text-emerald-600" />
              <span>
                Bạn là {userId === a.sellerId ? 'người bán (A)' : 'người mua (B)'}. Tiền chuyển trực
                tiếp B → A; cơ sở và SportHub không thu phí chuyển nhượng.
              </span>
            </p>
            {a.phase === 'WAITING_PAYMENT' && (
              <Countdown
                expiresAt={a.payBy}
                className="rounded-2xl border border-amber-200/80 px-4 py-3 text-sm font-bold"
              />
            )}
            {a.phase === 'WAITING_PAYMENT' && remaining === 0 && (
              <p className={amberNotice}>
                Đã hết hạn báo chuyển tiền. Tin sẽ được mở lại; không chuyển từ QR cũ. Nếu đã chuyển,
                hãy liên hệ người bán.
              </p>
            )}
            {!blocked && (
              <PeerPayment
                key={`${id}:${a.termsHash}`}
                attempt={a}
                userId={userId}
                reload={read.reload}
                remaining={remaining ?? 0}
              />
            )}
            {a.decision && (
              <p className={`${card} p-4 text-sm sm:p-5`}>
                Quyết định quyền sân:{' '}
                {a.decision.decision === 'BUYER_RECEIVES'
                  ? 'Người mua nhận quyền'
                  : a.phase === 'COMPLETED'
                    ? 'Đã bàn giao cho người mua'
                    : 'Người bán giữ quyền'}{' '}
                · {a.decision.reason}.
                {a.phase !== 'COMPLETED' &&
                  ' Nếu có khoản tiền chưa rõ, hai bên tự giải quyết với nhau và qua chủ sân; SportHub không xử lý khoản này.'}
              </p>
            )}
            {a.phase === 'COMPLETED' && userId === a.buyerId && (
              <Link href={`/customer/bookings/${a.bookingId}`} className={`${primaryPill} w-full`}>
                Mở lượt đặt và mã vào sân của bạn
              </Link>
            )}
            <div className="flex flex-wrap gap-2.5">
              <Button
                variant="outline"
                className={outlinePill}
                onClick={() => setShowChat((value) => !value)}
              >
                <MessagesSquare className="size-4" />
                {showChat ? 'Thu gọn trao đổi' : 'Mở trao đổi'}
              </Button>
            </div>
            {!blocked && showChat && <Chat id={a.conversationId} userId={userId} />}
          </>
        )}
        {timedOut && (
          <p className={amberNotice}>
            Đã dừng tự theo dõi tại màn hình. Tải lại để xem trạng thái mới; không chuyển tiền lần
            nữa khi chưa rõ kết quả.
          </p>
        )}
        <Button variant="outline" className={outlinePill} onClick={read.reload}>
          <RefreshCw className="size-4" />
          Tải lại trạng thái quyền sân
        </Button>
      </div>
    </main>
  )
}
