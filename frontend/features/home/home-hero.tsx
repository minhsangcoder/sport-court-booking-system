import { ArrowRight, CalendarClock, CheckCircle2, QrCode, ShieldCheck, Users } from 'lucide-react'
import Link from 'next/link'
import { HeroHeadline } from './hero-headline'
import { HeroTyper } from './hero-typer'
import { CourtLines, PickleBall, Shuttle, TennisBall } from './home-art'
import { HomeSearch } from './home-search'
export function HomeHero({ today }: { today: string }) {
  return (
    <section className="relative isolate bg-slate-950 text-white">
      <div className="absolute inset-0 -z-10 overflow-hidden">
        <picture>
          <source
            type="image/webp"
            srcSet="/images/hero-sunset-960.webp 960w, /images/hero-sunset-1920.webp 1920w"
            sizes="100vw"
          />
          <img
            src="/images/hero-sunset-1920.webp"
            alt=""
            fetchPriority="high"
            className="absolute inset-0 size-full object-cover object-[75%_center]"
          />
        </picture>
        <div className="absolute inset-0 bg-gradient-to-r from-slate-950 via-slate-950/70 to-transparent" />
        <div className="absolute inset-x-0 bottom-0 h-40 bg-gradient-to-t from-slate-950 to-transparent" />
        <CourtLines className="absolute -left-24 -top-10 w-[640px] rotate-[-8deg] text-white/[0.06]" />
      </div>

      <div
        className="pointer-events-none absolute inset-0 hidden overflow-hidden lg:block"
        aria-hidden
      >
        <TennisBall
          className="home-float absolute right-[8%] top-[18%] w-14 drop-shadow-xl"
          style={{ '--home-rot': '0deg' } as React.CSSProperties}
        />
        <PickleBall
          className="home-float absolute right-[30%] top-[10%] w-9 opacity-90 drop-shadow-lg [animation-delay:-2s]"
          style={{ '--home-rot': '10deg' } as React.CSSProperties}
        />
        <Shuttle
          className="home-float absolute right-[16%] top-[46%] w-12 drop-shadow-lg [animation-delay:-4s]"
          style={{ '--home-rot': '-12deg' } as React.CSSProperties}
        />
      </div>

      <div className="mx-auto max-w-7xl px-4 pb-28 pt-16 sm:px-6 md:pt-24 lg:pb-32 lg:pt-32">
        <div className="max-w-5xl">
          <div className="home-rise inline-flex items-center gap-2 rounded-full border border-white/20 bg-white/10 px-3 py-1 text-xs font-medium text-emerald-200 backdrop-blur-md">
            <span className="relative flex size-2">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-70" />
              <span className="relative inline-flex size-2 rounded-full bg-emerald-400" />
            </span>
            Ưu đãi hôm nay
          </div>

          <div className="home-rise mt-5 [animation-delay:80ms]">
            <HeroHeadline />
          </div>

          <div className="home-rise mt-8 max-w-2xl [animation-delay:160ms]">
            <HeroTyper />
          </div>

          <div className="home-rise mt-7 flex flex-wrap items-center gap-3 [animation-delay:240ms]">
            <Link
              href="/search"
              className="group inline-flex h-12 items-center gap-2 rounded-full bg-emerald-500 px-6 text-sm font-bold text-slate-950 shadow-[0_8px_32px_-6px_rgba(16,185,129,0.7)] transition hover:bg-emerald-400"
            >
              Đặt sân ngay
              <ArrowRight size={16} className="transition-transform group-hover:translate-x-1" />
            </Link>
            <a
              href="#cach-hoat-dong"
              className="inline-flex h-12 items-center rounded-full border border-white/30 bg-white/5 px-6 text-sm font-semibold text-white backdrop-blur-md transition hover:bg-white/15"
            >
              Xem cách hoạt động
            </a>
          </div>

          <ul className="home-rise mt-8 flex flex-wrap gap-x-6 gap-y-2 text-sm text-slate-200 [animation-delay:320ms]">
            {[
              [CheckCircle2, 'Đồng bộ lịch chủ sân'],
              [QrCode, 'Mã vào sân động 2 phút'],
              [ShieldCheck, 'Thanh toán SePay xác thực'],
            ].map(([Icon, label]) => {
              const I = Icon as typeof CheckCircle2
              return (
                <li key={label as string} className="inline-flex items-center gap-1.5">
                  <I size={16} className="text-emerald-400" />
                  {label as string}
                </li>
              )
            })}
          </ul>
        </div>

        <div
          className="pointer-events-none absolute bottom-40 right-6 hidden w-64 space-y-3 xl:block"
          aria-hidden
        >
          <div className="home-float rounded-2xl border border-white/20 bg-white/10 p-3.5 shadow-2xl backdrop-blur-xl">
            <div className="flex items-center gap-3">
              <span className="flex size-9 items-center justify-center rounded-xl bg-emerald-500/90 text-slate-950">
                <CalendarClock size={18} />
              </span>
              <div>
                <p className="text-xs text-slate-300">Hôm nay 19:00</p>
                <p className="text-sm font-bold">Còn sân trống</p>
              </div>
            </div>
          </div>
          <div className="home-float ml-8 rounded-2xl border border-white/20 bg-white/10 p-3.5 shadow-2xl backdrop-blur-xl [animation-delay:-3s]">
            <div className="flex items-center gap-3">
              <span className="flex size-9 items-center justify-center rounded-xl bg-amber-400/90 text-slate-950">
                <Users size={18} />
              </span>
              <div>
                <p className="text-xs text-slate-300">Chia tiền nhóm</p>
                <p className="text-sm font-bold">Góp qua QR SePay</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="relative z-10 mx-auto -mb-16 max-w-7xl px-4 sm:px-6 lg:-mb-20">
        <HomeSearch today={today} />
      </div>
    </section>
  )
}
