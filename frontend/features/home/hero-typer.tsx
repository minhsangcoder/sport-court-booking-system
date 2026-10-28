'use client'
import { useEffect, useState, useSyncExternalStore } from 'react'

type Seg = { t: string; c?: string }
const LINES: Seg[][] = [
  [
    { t: 'Pickleball', c: 'text-lime-300' },
    { t: ', ' },
    { t: 'Quần vợt', c: 'text-amber-300' },
    { t: ', ' },
    { t: 'Cầu lông', c: 'text-sky-300' },
    { t: ', ' },
    { t: 'Bóng đá', c: 'text-orange-300' },
    { t: '.' },
  ],
  [
    { t: 'Giữ chỗ trực tuyến', c: 'text-emerald-300' },
    { t: ', chia tiền nhóm, ' },
    { t: 'chuyển nhượng lịch', c: 'text-fuchsia-300' },
    { t: ' cho bạn bè' },
  ],
  [{ t: 'và vào sân bằng ' }, { t: 'mã QR', c: 'text-yellow-300' }, { t: '.' }],
]
const lengths = LINES.map((l) => l.reduce((n, s) => n + s.t.length, 0))

function Partial({ segs, count }: { segs: Seg[]; count: number }) {
  return (
    <>
      {segs.map((s, i) => {
        const start = segs.slice(0, i).reduce((n, x) => n + x.t.length, 0)
        const text = s.t.slice(0, Math.max(0, count - start))
        if (!text) return null
        return (
          <span key={i} className={s.c ? `${s.c} font-semibold` : undefined}>
            {text}
          </span>
        )
      })}
    </>
  )
}

const reducedQuery = '(prefers-reduced-motion: reduce)'
const subscribeReduced = (cb: () => void) => {
  const m = window.matchMedia(reducedQuery)
  m.addEventListener('change', cb)
  return () => m.removeEventListener('change', cb)
}

/** Types each line in turn, holds, clears and loops. Hidden full text reserves the height. */
export function HeroTyper() {
  const [line, setLine] = useState(0)
  const [chars, setChars] = useState(0)
  const still = useSyncExternalStore(
    subscribeReduced,
    () => window.matchMedia(reducedQuery).matches,
    () => false,
  )

  useEffect(() => {
    if (still) return
    let delay = 32 + Math.random() * 40
    let next: () => void
    if (chars < lengths[line]) next = () => setChars((c) => c + 1)
    else if (line < LINES.length - 1) {
      delay = 380
      next = () => {
        setLine((l) => l + 1)
        setChars(0)
      }
    } else {
      delay = 2600
      next = () => {
        setLine(0)
        setChars(0)
      }
    }
    const id = window.setTimeout(next, delay)
    return () => window.clearTimeout(id)
  }, [line, chars, still])

  return (
    <div className="space-y-1 text-base leading-relaxed text-slate-200 sm:text-lg">
      {LINES.map((segs, i) => {
        const shown = still || i < line ? lengths[i] : i === line ? chars : 0
        const full = segs.map((s) => s.t).join('')
        return (
          <p key={i} className="relative">
            <span className="invisible">{full}</span>
            <span aria-hidden className="absolute inset-0">
              <Partial segs={segs} count={shown} />
              {!still && i === line && (
                <span className="ml-0.5 inline-block h-[1.05em] w-[2px] translate-y-[0.18em] animate-pulse bg-emerald-300" />
              )}
            </span>
            <span className="sr-only">{full}</span>
          </p>
        )
      })}
    </div>
  )
}
