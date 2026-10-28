// Decorative vector art for the landing page. Everything is aria-hidden and pointer-events-none.
import type { CSSProperties } from 'react'
type ArtProps = { className?: string; style?: CSSProperties }

/** Tennis/pickleball court line drawing, used as a faint texture. */
export function CourtLines({ className, style }: ArtProps) {
  return (
    <svg
      aria-hidden
      viewBox="0 0 600 340"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      className={className}
      style={style}
    >
      <rect x="20" y="20" width="560" height="300" rx="4" />
      <rect x="20" y="62" width="560" height="216" />
      <line x1="300" y1="20" x2="300" y2="320" strokeWidth="3" />
      <line x1="160" y1="62" x2="160" y2="278" />
      <line x1="440" y1="62" x2="440" y2="278" />
      <line x1="160" y1="170" x2="440" y2="170" />
      <circle cx="300" cy="170" r="3" fill="currentColor" />
    </svg>
  )
}

/** Pickleball with perforations. */
export function PickleBall({ className, style }: ArtProps) {
  const holes: [number, number][] = [
    [32, 26],
    [50, 24],
    [66, 34],
    [24, 44],
    [42, 42],
    [60, 52],
    [74, 54],
    [30, 62],
    [48, 62],
    [64, 72],
    [38, 78],
    [54, 82],
  ]
  return (
    <svg aria-hidden viewBox="0 0 100 100" className={className} style={style}>
      <defs>
        <radialGradient id="pb-fill" cx="35%" cy="30%" r="80%">
          <stop offset="0" stopColor="#fde68a" />
          <stop offset="1" stopColor="#f59e0b" />
        </radialGradient>
      </defs>
      <circle cx="50" cy="50" r="46" fill="url(#pb-fill)" />
      {holes.map(([x, y]) => (
        <circle key={`${x}-${y}`} cx={x} cy={y} r="4.2" fill="#b45309" opacity="0.55" />
      ))}
    </svg>
  )
}

/** Tennis ball. */
export function TennisBall({ className, style }: ArtProps) {
  return (
    <svg aria-hidden viewBox="0 0 100 100" className={className} style={style}>
      <defs>
        <radialGradient id="tb-fill" cx="35%" cy="30%" r="80%">
          <stop offset="0" stopColor="#ecfccb" />
          <stop offset="1" stopColor="#a3e635" />
        </radialGradient>
      </defs>
      <circle cx="50" cy="50" r="46" fill="url(#tb-fill)" />
      <path
        d="M10 30c22 6 36 22 36 46M90 70C68 64 54 48 54 24"
        fill="none"
        stroke="#fff"
        strokeWidth="4"
        strokeLinecap="round"
        opacity="0.85"
      />
    </svg>
  )
}

/** Badminton shuttlecock. */
export function Shuttle({ className, style }: ArtProps) {
  return (
    <svg aria-hidden viewBox="0 0 100 100" className={className} style={style}>
      <path d="M36 18h28l14 46H22z" fill="#f8fafc" opacity="0.95" />
      <path
        d="M36 18 40 64M50 18v46M64 18 60 64M28 40h44"
        stroke="#94a3b8"
        strokeWidth="1.6"
        fill="none"
      />
      <path d="M22 64h56a6 6 0 0 1 0 8H22a6 6 0 0 1 0-8z" fill="#ef4444" />
      <path d="M26 72h48c0 10-10 18-24 18S26 82 26 72z" fill="#fbbf24" />
    </svg>
  )
}

/** Paddle/racket outline. */
export function Paddle({ className, style }: ArtProps) {
  return (
    <svg
      aria-hidden
      viewBox="0 0 100 140"
      fill="none"
      stroke="currentColor"
      strokeWidth="3"
      strokeLinecap="round"
      className={className}
      style={style}
    >
      <rect x="14" y="6" width="72" height="82" rx="34" />
      <path d="M44 88v22h12V88" />
      <rect x="42" y="110" width="16" height="24" rx="5" />
      <circle cx="38" cy="36" r="2" fill="currentColor" />
      <circle cx="62" cy="36" r="2" fill="currentColor" />
      <circle cx="50" cy="52" r="2" fill="currentColor" />
      <circle cx="38" cy="68" r="2" fill="currentColor" />
      <circle cx="62" cy="68" r="2" fill="currentColor" />
    </svg>
  )
}

/** Dotted grid texture. */
export function DotGrid({ className, style }: ArtProps) {
  return (
    <svg aria-hidden className={className} style={style} width="100%" height="100%">
      <defs>
        <pattern id="dot-grid" width="22" height="22" patternUnits="userSpaceOnUse">
          <circle cx="2" cy="2" r="1.4" fill="currentColor" />
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill="url(#dot-grid)" />
    </svg>
  )
}

/** Curved divider; `fill` comes from the parent's text colour. */
export function WaveDivider({ className, style }: ArtProps) {
  return (
    <svg
      aria-hidden
      viewBox="0 0 1440 80"
      preserveAspectRatio="none"
      className={className}
      style={style}
      fill="currentColor"
    >
      <path d="M0 40c160 44 320 44 480 22s320-62 480-48 320 46 480 24V80H0z" />
    </svg>
  )
}

/** Dashed curved connector between steps. */
export function DashedArc({ className, style }: ArtProps) {
  return (
    <svg
      aria-hidden
      viewBox="0 0 200 40"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeDasharray="5 7"
      strokeLinecap="round"
      className={className}
      style={style}
    >
      <path d="M2 30C50 -6 150 -6 198 30" />
    </svg>
  )
}
