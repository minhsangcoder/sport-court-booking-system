'use client'
import { useState } from 'react'

/** The account's photo, or its first initial when there is none or the photo fails to load. */
export function UserAvatar({
  name,
  src,
  className = '',
}: {
  name?: string | null
  src?: string | null
  className?: string
}) {
  const [failed, setFailed] = useState('')
  return (
    <div
      className={`flex shrink-0 items-center justify-center overflow-hidden bg-slate-900 font-semibold text-white dark:bg-emerald-700 ${className}`}
    >
      {src && failed !== src ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={src}
          alt=""
          className="size-full object-cover"
          onError={() => setFailed(src)}
        />
      ) : name ? (
        name.charAt(0).toUpperCase()
      ) : (
        'U'
      )}
    </div>
  )
}
