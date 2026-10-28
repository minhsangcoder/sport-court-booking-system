const line1 = ['Đặt', 'sân', 'thể', 'thao']
const line2 = ['nhanh', 'như', 'một', 'cú', 'chạm']

/** Staggered word reveal; the second line carries a flowing colour gradient and a hand-drawn underline. */
export function HeroHeadline() {
  return (
    <h1 className="font-display text-[3.4rem] font-extrabold uppercase italic leading-[1.05] tracking-tight sm:text-7xl lg:text-7xl xl:text-8xl">
      <span className="block">
        {line1.map((w, i) => (
          <span key={w} className="hl-word pr-[0.22em]" style={{ animationDelay: `${i * 90}ms` }}>
            {w}
          </span>
        ))}
      </span>
      <span className="relative mt-1 block w-fit sm:whitespace-nowrap">
        {line2.map((w, i) => (
          <span
            key={w}
            className="hl-word pr-[0.22em]"
            style={{ animationDelay: `${420 + i * 90}ms` }}
          >
            <span className="hl-flow">{w}</span>
          </span>
        ))}
        <svg
          aria-hidden
          viewBox="0 0 400 18"
          preserveAspectRatio="none"
          className="absolute -bottom-5 left-0 h-3 w-full"
        >
          <path
            d="M4 12C70 2 140 16 210 8s130-6 186 2"
            fill="none"
            stroke="url(#hl-line)"
            strokeWidth="5"
            strokeLinecap="round"
            className="hl-stroke"
          />
          <defs>
            <linearGradient id="hl-line" x1="0" x2="1">
              <stop offset="0" stopColor="#6ee7b7" />
              <stop offset="0.55" stopColor="#fcd34d" />
              <stop offset="1" stopColor="#fb923c" />
            </linearGradient>
          </defs>
        </svg>
      </span>
    </h1>
  )
}
