'use client'
import { CalendarCheck2, MapPin, Repeat, ShieldCheck, Users } from 'lucide-react'
import { LocalCourtImage as Image } from '@/components/local-court-image'

export function AuthShowcase() {
  return (
    <div className="relative hidden w-full overflow-hidden rounded-xl border border-slate-800 bg-slate-950 lg:col-span-6 lg:flex lg:flex-col lg:justify-between p-6 xl:p-7 text-white min-h-[500px]">
      <div className="absolute inset-0 z-0">
        <Image
          src="/images/hero-pickleball.png"
          alt="Sân thể thao SportHub"
          fill
          className="object-cover object-center opacity-35"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/80 to-slate-950/60" />
      </div>

      <div className="relative z-10 flex items-center justify-between gap-3">
        <div className="inline-flex items-center gap-2 rounded-md border border-slate-700/80 bg-slate-900/90 px-2.5 py-1 text-[11px] font-medium text-slate-200 whitespace-nowrap">
          <span className="size-1.5 rounded-full bg-emerald-400" />
          <span>Hệ thống đặt sân trực tuyến</span>
        </div>
        <div className="flex items-center gap-1.5 text-[11px] text-slate-300 font-medium whitespace-nowrap">
          <MapPin size={12} strokeWidth={1.75} className="text-emerald-400 shrink-0" />
          <span>Toàn quốc</span>
        </div>
      </div>

      <div className="relative z-10 space-y-5 my-auto py-5">
        <h2 className="text-xl xl:text-2xl font-bold tracking-tight text-white leading-snug">
          Quản lý lịch thi đấu, đặt nhóm và chuyển nhượng minh bạch.
        </h2>
        <p className="text-slate-300 text-xs leading-relaxed max-w-md">
          Đặt sân Pickleball, Cầu lông, quần vợt & Bóng đá theo thời gian thực. Xác nhận vào sân
          bằng mã QR, chia tiền nhóm và chuyển nhượng linh hoạt.
        </p>

        <div className="grid gap-2.5 pt-1">
          <div className="flex items-start gap-3 rounded-lg border border-slate-800 bg-slate-900/75 p-3 backdrop-blur-xs">
            <div className="flex size-8 items-center justify-center rounded-md border border-slate-700 bg-slate-800 text-emerald-400 shrink-0">
              <CalendarCheck2 size={15} strokeWidth={1.75} />
            </div>
            <div>
              <div className="text-xs font-semibold text-white">Đặt sân & Giữ chỗ tức thì</div>
              <div className="mt-0.5 text-[11px] text-slate-400">
                Lịch khung giờ trống thời gian thực, đối soát thanh toán tự động
              </div>
            </div>
          </div>

          <div className="flex items-start gap-3 rounded-lg border border-slate-800 bg-slate-900/75 p-3 backdrop-blur-xs">
            <div className="flex size-8 items-center justify-center rounded-md border border-slate-700 bg-slate-800 text-emerald-400 shrink-0">
              <Users size={15} strokeWidth={1.75} />
            </div>
            <div>
              <div className="text-xs font-semibold text-white">Đặt sân nhóm & Chia tiền</div>
              <div className="mt-0.5 text-[11px] text-slate-400">
                Mời thành viên qua mã QR, tự động tính tỷ lệ đóng góp
              </div>
            </div>
          </div>

          <div className="flex items-start gap-3 rounded-lg border border-slate-800 bg-slate-900/75 p-3 backdrop-blur-xs">
            <div className="flex size-8 items-center justify-center rounded-md border border-slate-700 bg-slate-800 text-emerald-400 shrink-0">
              <Repeat size={15} strokeWidth={1.75} />
            </div>
            <div>
              <div className="text-xs font-semibold text-white">
                Sàn chuyển nhượng khung giờ sân
              </div>
              <div className="mt-0.5 text-[11px] text-slate-400">
                Nhượng lại khung giờ trực tiếp và sang tên vé điện tử an toàn
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="relative z-10 flex items-center justify-between border-t border-slate-800/80 pt-3.5 text-[11px] text-slate-400">
        <span className="inline-flex items-center gap-1.5 font-medium text-slate-300">
          <ShieldCheck size={13} strokeWidth={1.75} className="text-emerald-400" />
          Xác thực SePay & QR Check-in
        </span>
        <span>Chuẩn vận hành cụm sân</span>
      </div>
    </div>
  )
}
