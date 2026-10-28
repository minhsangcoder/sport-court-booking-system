'use client'
import { useEffect, useState } from 'react'
import { CourtLines, PickleBall } from './home-art'

const AUTOPLAY_MS = 6000
const specs = [
  {
    title: 'Đồng bộ khung giờ thực tế',
    description: 'Lịch và giá lấy trực tiếp từ chủ sân, không trùng lịch.',
    image: 'hero-tennis',
    fact: 'Bước đặt 30 phút',
  },
  {
    title: 'Mã vào sân bảo mật',
    description: 'QR vào sân tự làm mới, chỉ người đặt mới thấy.',
    image: 'hero-paddle',
    fact: 'QR làm mới mỗi 2 phút',
  },
  {
    title: 'Chia tiền nhóm minh bạch',
    description: 'Chia phần cho từng người, theo dõi ai đã góp.',
    image: 'hero-pickleball',
    fact: 'Tạo nhóm trước 15 phút',
  },
  {
    title: 'Chuyển nhượng trực tiếp P2P',
    description: 'Bận thì nhượng lại lịch, người bán nhận 100% giá.',
    image: 'court-aerial',
    fact: '0% phí dịch vụ',
  },
]

export function HomeBenefits() {
  const [active, setActive] = useState(0)
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    if (paused || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const id = window.setTimeout(() => setActive((i) => (i + 1) % specs.length), AUTOPLAY_MS)
    return () => window.clearTimeout(id)
  }, [active, paused])

  return (
    <section className="relative isolate overflow-hidden bg-slate-950 py-14 text-white">
      <div aria-hidden className="pointer-events-none absolute inset-0 -z-10">
        <div className="absolute -left-32 top-0 size-[480px] rounded-full bg-emerald-500/20 blur-3xl" />
        <div className="absolute -right-24 bottom-0 size-[420px] rounded-full bg-amber-400/15 blur-3xl" />
        <CourtLines className="absolute -right-20 top-10 w-[640px] rotate-6 text-white/[0.06]" />
        <PickleBall className="home-float absolute right-[7%] top-12 hidden w-12 opacity-90 lg:block" />
      </div>
      <div className="mx-auto w-full max-w-7xl px-4 sm:px-6">
        <div className="max-w-2xl">
          <p className="text-xs font-semibold uppercase tracking-wider text-emerald-400">
            Vì sao chơi tại SportHub
          </p>
          <h2 className="mt-2 text-xl font-extrabold tracking-tight text-white sm:text-2xl">
            Bớt nhắn tin, thêm thời gian trên sân
          </h2>
        </div>

        <div
          className="mt-6 grid items-stretch gap-6 lg:grid-cols-12 lg:gap-10"
          onMouseEnter={() => setPaused(true)}
          onMouseLeave={() => setPaused(false)}
        >
          <div
            className="relative isolate min-h-[240px] overflow-hidden rounded-2xl bg-slate-900 shadow-2xl shadow-slate-900/20 lg:col-span-7 lg:min-h-[300px]"
            aria-hidden
          >
            {specs.map((s, i) => (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                key={s.image}
                src={`/images/${s.image}-1280.webp`}
                srcSet={`/images/${s.image}-640.webp 640w, /images/${s.image}-1280.webp 1280w`}
                sizes="(max-width: 1024px) 100vw, 58vw"
                alt=""
                loading={i === 0 ? 'eager' : 'lazy'}
                className={`absolute inset-0 size-full object-cover transition-all duration-[900ms] ease-out ${
                  i === active ? 'scale-100 opacity-100' : 'scale-110 opacity-0'
                }`}
              />
            ))}
            <div className="absolute inset-0 bg-gradient-to-t from-slate-950/75 via-transparent to-slate-950/10" />
            <div className="absolute bottom-5 left-5 overflow-hidden rounded-full border border-white/25 bg-white/15 px-4 py-2 text-sm font-semibold text-white backdrop-blur-md">
              <span key={active} className="home-rise inline-block">
                {specs[active].fact}
              </span>
            </div>
            <span className="absolute right-5 top-5 font-mono text-xs font-semibold tabular-nums text-white/80">
              {String(active + 1).padStart(2, '0')} / {String(specs.length).padStart(2, '0')}
            </span>
          </div>

          <ul className="flex flex-col gap-2 lg:col-span-5">
            {specs.map((s, i) => {
              const on = i === active
              return (
                <li key={s.title}>
                  <button
                    type="button"
                    onClick={() => setActive(i)}
                    onFocus={() => setActive(i)}
                    aria-expanded={on}
                    className={`group relative w-full cursor-pointer overflow-hidden rounded-xl border px-4 py-3 text-left transition-all duration-300 ${
                      on
                        ? 'border-emerald-400/50 bg-white/10 shadow-xl shadow-emerald-500/10 backdrop-blur-md'
                        : 'border-white/5 bg-white/[0.04] hover:bg-white/10'
                    }`}
                  >
                    <div className="flex items-center gap-4">
                      <span
                        className={`font-mono text-sm font-semibold tabular-nums transition-colors ${
                          on ? 'text-emerald-400' : 'text-slate-500'
                        }`}
                      >
                        {String(i + 1).padStart(2, '0')}
                      </span>
                      <h3
                        className={`text-base font-bold transition-colors ${
                          on ? 'text-white' : 'text-slate-400 group-hover:text-slate-100'
                        }`}
                      >
                        {s.title}
                      </h3>
                    </div>
                    <div
                      className={`grid transition-all duration-500 ease-out ${
                        on ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'
                      }`}
                    >
                      <p className="overflow-hidden pl-9 pt-0 text-sm leading-relaxed text-slate-300">
                        <span className="block pt-2.5">{s.description}</span>
                      </p>
                    </div>
                    {on && (
                      <span
                        key={`${active}-${paused}`}
                        className="absolute bottom-0 left-0 h-0.5 bg-emerald-500"
                        style={
                          paused
                            ? { width: '100%' }
                            : { animation: `benefitFill ${AUTOPLAY_MS}ms linear forwards` }
                        }
                      />
                    )}
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      </div>
    </section>
  )
}
