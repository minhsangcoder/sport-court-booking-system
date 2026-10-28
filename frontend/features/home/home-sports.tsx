'use client'
import { ArrowUpRight } from 'lucide-react'
import Link from 'next/link'
import { useState } from 'react'

const featuredSports = [
  {
    name: 'Pickleball',
    spec: 'Sân mái che & ngoài trời',
    image: 'hero-pickleball',
    tagline: 'Mặt sân Acrylic tiêu chuẩn, vạch bếp rõ nét, đặt lẻ hoặc ghép nhóm.',
  },
  {
    name: 'Tennis',
    spec: 'Hardcourt tiêu chuẩn',
    image: 'hero-tennis',
    tagline: 'Mặt sân cứng chuẩn thi đấu, hệ thống đèn LED chống chói buổi tối.',
  },
  {
    name: 'Cầu lông',
    spec: 'Thảm PVC trong nhà',
    image: 'hero-paddle',
    tagline: 'Sân thảm tiêu chuẩn BWF, trần cao thoáng khí và ánh sáng đồng đều.',
  },
  {
    name: 'Bóng đá',
    spec: 'Cỏ nhân tạo, sân 5 & 7 người',
    image: 'court-aerial',
    tagline: 'Cỏ nhân tạo độ đàn hồi cao, hệ thống thoát nước và lưới chắn an toàn.',
  },
]

export function HomeSports({ today }: { today: string }) {
  const [active, setActive] = useState(0)
  return (
    <section className="mx-auto w-full max-w-7xl px-4 py-12 sm:px-6">
      <div className="flex flex-col justify-between gap-2 sm:flex-row sm:items-end">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wider text-emerald-700 dark:text-emerald-400">
            Danh mục bộ môn
          </p>
          <h2 className="mt-1 text-xl font-extrabold tracking-tight text-slate-900 sm:text-2xl dark:text-white">
            Chọn sân theo môn thể thao
          </h2>
        </div>
        <Link
          href="/search"
          className="inline-flex items-center gap-1 text-sm font-semibold text-emerald-700 hover:text-emerald-800 dark:text-emerald-400"
        >
          <span>Xem tất cả danh mục</span>
          <ArrowUpRight size={15} />
        </Link>
      </div>

      <div
        className="mt-6 flex flex-col gap-3 lg:h-[300px] lg:flex-row"
        onMouseLeave={() => setActive(0)}
      >
        {featuredSports.map((sport, i) => {
          const on = i === active
          return (
            <Link
              key={sport.name}
              href={`/search?q=${encodeURIComponent(sport.name)}&date=${today}`}
              onMouseEnter={() => setActive(i)}
              onFocus={() => setActive(i)}
              aria-label={`${sport.name}: xem sân trống`}
              className={`group relative isolate block min-h-24 overflow-hidden rounded-3xl bg-slate-900 outline-offset-4 transition-[flex-grow,height] duration-700 ease-[cubic-bezier(0.22,1,0.36,1)] lg:h-auto lg:min-h-0 lg:basis-0 ${
                on ? 'h-60 lg:flex-[3]' : 'h-24 lg:flex-1'
              }`}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={`/images/${sport.image}-1280.webp`}
                srcSet={`/images/${sport.image}-640.webp 640w, /images/${sport.image}-1280.webp 1280w`}
                sizes="(max-width: 1024px) 100vw, 50vw"
                alt=""
                loading="lazy"
                className={`absolute inset-0 -z-10 size-full object-cover transition-transform duration-[1200ms] ease-out ${
                  on ? 'scale-100' : 'scale-125'
                }`}
              />
              <div
                className={`absolute inset-0 -z-10 transition-colors duration-700 ${
                  on
                    ? 'bg-gradient-to-t from-slate-950/85 via-slate-950/15 to-transparent'
                    : 'bg-slate-950/55'
                }`}
              />

              {/* collapsed label */}
              <div
                className={`absolute inset-0 flex items-center px-6 transition-opacity duration-300 lg:items-end lg:pb-7 ${
                  on ? 'opacity-0' : 'opacity-100 delay-300'
                }`}
              >
                <span className="text-lg font-bold text-white lg:[writing-mode:vertical-rl] lg:rotate-180">
                  {sport.name}
                </span>
              </div>

              {/* expanded content */}
              <div className="absolute inset-x-0 bottom-0 p-5">
                <div
                  className={`transition-all duration-500 ${
                    on
                      ? 'translate-y-0 opacity-100 delay-200'
                      : 'pointer-events-none translate-y-4 opacity-0'
                  }`}
                >
                  <span className="inline-block rounded-full border border-white/25 bg-white/15 px-3 py-1 text-xs font-medium text-white backdrop-blur-md">
                    {sport.spec}
                  </span>
                  <h3 className="mt-3 text-xl font-extrabold tracking-tight text-white">
                    {sport.name}
                  </h3>
                  <p className="mt-1.5 max-w-sm text-xs leading-relaxed text-slate-200">
                    {sport.tagline}
                  </p>
                  <span className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-white px-3.5 py-1.5 text-xs font-bold text-slate-900 transition-colors group-hover:bg-emerald-400">
                    Xem sân trống
                    <ArrowUpRight
                      size={15}
                      className="transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5"
                    />
                  </span>
                </div>
              </div>
            </Link>
          )
        })}
      </div>
    </section>
  )
}
