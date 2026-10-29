'use client'
import { useEffect, useState } from 'react'
import { api } from '@/lib/api'
import type { Quote, Slot } from '@/features/schedule/types'

export function selectionInterval(courtId: string, selection: Slot[]) {
  if (selection.length === 0) return { interval: null, error: '' }
  for (let i = 1; i < selection.length; i++)
    if (selection[i - 1].endsAt !== selection[i].startsAt)
      return { interval: null, error: 'Chọn các khung giờ liên tiếp cho một lượt đặt.' }
  const startsAt = selection[0].startsAt,
    endsAt = selection[selection.length - 1].endsAt
  const minutes = (Date.parse(endsAt) - Date.parse(startsAt)) / 60000
  if (minutes < 30)
    return { interval: null, error: 'Mỗi lượt đặt phải từ 30 phút trở lên.' }
  return { interval: { courtId, startsAt, endsAt }, error: '' }
}

export function useIntervalQuote(courtId: string, selection: Slot[], withAdvice = true) {
  const { interval, error } = selectionInterval(courtId, selection)
  const path = withAdvice ? '/bookings/quote' : '/schedules/public/quote'
  const key = interval ? JSON.stringify({ path, interval }) : ''
  const [result, setResult] = useState<{ key: string; quote: Quote | null; error: string } | null>(
    null,
  )
  useEffect(() => {
    if (!key) return
    const controller = new AbortController()
    const timer = setTimeout(() => {
      const request = JSON.parse(key)
      api<Quote>(request.path, {
        method: 'POST',
        body: JSON.stringify(request.interval),
        signal: controller.signal,
      })
        .then((quote) => {
          if (!controller.signal.aborted) setResult({ key, quote, error: '' })
        })
        .catch((failure) => {
          if (!controller.signal.aborted)
            setResult({
              key,
              quote: null,
              error: failure instanceof Error ? failure.message : 'Không thể lấy báo giá.',
            })
        })
    }, 200)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [key])
  const current = result?.key === key ? result : null
  return {
    interval,
    quote: current?.quote ?? null,
    error: error || current?.error || '',
    loading: !!key && !current,
  }
}
