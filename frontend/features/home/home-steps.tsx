import { CalendarSearch, QrCode, Wallet } from 'lucide-react'
import { DashedArc, DotGrid, Paddle, PickleBall, Shuttle } from './home-art'
const steps = [
  {
    icon: CalendarSearch,
    title: 'Chọn sân & khung giờ',
    text: 'Lọc theo môn, khu vực và ngày chơi.',
  },
  {
    icon: Wallet,
    title: 'Giữ chỗ & thanh toán',
    text: 'Trả một mình hoặc chia tiền nhóm.',
  },
  {
    icon: QrCode,
    title: 'Quét mã vào sân',
    text: 'Quét mã QR là vào.',
  },
]
export function HomeSteps() {
  return (
    <section
      id="cach-hoat-dong"
      className="relative scroll-mt-20 overflow-hidden bg-gradient-to-b from-emerald-50/80 via-transparent to-transparent py-14 dark:from-emerald-950/40 dark:via-transparent"
    >
      <DotGrid className="pointer-events-none absolute inset-0 -z-10 text-slate-300/60 [mask-image:radial-gradient(ellipse_at_center,black,transparent_70%)] dark:text-slate-700/50" />
      <Paddle className="home-float pointer-events-none absolute left-[4%] top-16 hidden h-28 text-emerald-700/25 [--home-rot:-14deg] lg:block" />
      <PickleBall className="home-float pointer-events-none absolute right-[6%] top-24 hidden w-14 [animation-delay:-2s] lg:block" />
      <Shuttle className="home-float pointer-events-none absolute bottom-10 right-[14%] hidden w-12 [animation-delay:-4s] [--home-rot:12deg] lg:block" />
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <div className="mx-auto max-w-2xl text-center">
          <p className="text-xs font-semibold uppercase tracking-wider text-emerald-700 dark:text-emerald-400">
            Cách hoạt động
          </p>
          <h2 className="mt-2 text-xl font-extrabold tracking-tight text-slate-900 sm:text-2xl dark:text-white">
            Ra sân chỉ với 3 bước
          </h2>
        </div>
        <div className="relative mt-8 grid gap-6 md:grid-cols-3">
          {steps.map((s, i) => {
            const Icon = s.icon
            return (
              <div key={s.title} className="relative text-center">
                {i < steps.length - 1 && (
                  <DashedArc className="absolute left-[calc(50%+2.5rem)] top-1 hidden h-8 w-[calc(100%-5rem)] text-emerald-400/70 md:block" />
                )}
                <div className="relative mx-auto flex size-14 items-center justify-center rounded-2xl bg-emerald-700 text-white shadow-md">
                  <Icon size={22} strokeWidth={1.6} />
                  <span className="absolute -right-1.5 -top-1.5 flex size-5 items-center justify-center rounded-full bg-slate-900 text-[11px] font-bold text-white ring-4 ring-slate-50 dark:ring-slate-950">
                    {i + 1}
                  </span>
                </div>
                <h3 className="mt-4 text-base font-bold text-slate-900 dark:text-white">
                  {s.title}
                </h3>
                <p className="mx-auto mt-2 max-w-xs text-xs leading-relaxed text-slate-600 dark:text-slate-400">
                  {s.text}
                </p>
              </div>
            )
          })}
        </div>
      </div>
    </section>
  )
}
