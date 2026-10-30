'use client'
import { useEffect, useState } from 'react'
import { privateImage } from '@/lib/api'
import { useAuth } from '@/lib/auth-context'

export function PrivateImage(props: { path: string; alt: string; className?: string }) {
  const { user } = useAuth()
  return <ImageContent key={`${user?.id}:${props.path}`} {...props} />
}
function ImageContent({ path, alt, className }: { path: string; alt: string; className?: string }) {
  const [url, setUrl] = useState('')
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    let objectUrl = ''
    void privateImage(path, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
        setError('')
      })
      .catch((failure) => {
        if (!controller.signal.aborted)
          setError(failure instanceof Error ? failure.message : 'Chưa tải được ảnh.')
      })
    return () => {
      controller.abort()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [path, retry])
  if (error)
    return (
      <p role="alert">
        {error}{' '}
        <button className="underline" type="button" onClick={() => setRetry((value) => value + 1)}>
          Tải lại ảnh
        </button>
      </p>
    )
  // Private authenticated images use short-lived browser object URLs only.
  return url ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={url}
      alt={alt}
      className={className ?? 'h-auto max-w-full rounded-lg'}
      referrerPolicy="no-referrer"
    />
  ) : (
    <p role="status">Đang tải ảnh…</p>
  )
}
