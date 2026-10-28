'use client'
import { Button } from '@/components/ui/button'
import { Card, Status } from '@/features/integration/shared'
import { type MemberTicket } from '@/lib/business-api'
import { useApi } from '@/lib/use-api'
import { usePolling } from '@/lib/use-polling'
import Image from 'next/image'

export function MemberQr({ id, owner }: { id: string; owner: boolean }) {
  const r = useApi<MemberTicket>(`/groups/${id}/attendance/ticket`)
  const all = useApi<MemberTicket[]>(owner ? `/groups/${id}/attendance/tickets` : null)
  const reload = r.reload,
    reloadAll = all.reload
  usePolling(() => Promise.all([reload(), reloadAll()]), { interval: 60000, scope: id })
  return (
    <Card title="QR điểm danh của tôi">
      <Status loading={r.loading} error={r.error} />
      {r.data && (
        <>
          <Image
            unoptimized
            src={r.data.qrPngDataUrl}
            width={220}
            height={220}
            alt="QR điểm danh thành viên"
          />
          <p className="text-sm text-slate-500">
            Chỉ dùng trong khung giờ xác nhận vào sân. Mã hết hạn{' '}
            {new Date(r.data.expiresAt).toLocaleTimeString('vi-VN')}.
          </p>
          <Button
            variant="outline"
            onClick={() => {
              r.reload()
              all.reload()
            }}
          >
            Làm mới QR cá nhân
          </Button>
          {owner && (
            <details>
              <summary>QR các thành viên (chỉ người tổ chức)</summary>
              <Status error={all.error} />
              {all.data?.map((t) => (
                <div key={t.memberId} className="my-4">
                  <p className="break-all text-xs">Thành viên {t.memberId}</p>
                  <Image
                    unoptimized
                    src={t.qrPngDataUrl}
                    width={180}
                    height={180}
                    alt="QR thành viên do người tổ chức cung cấp"
                  />
                </div>
              ))}
            </details>
          )}
        </>
      )}
    </Card>
  )
}
