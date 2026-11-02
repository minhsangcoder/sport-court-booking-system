'use client'
import { Check, Copy } from 'lucide-react'
import { useState } from 'react'
import { outlinePill } from './owner-ui'

/** Copies on click. If the browser refuses the clipboard, the value is shown to copy by hand. */
export function CopyButton({
  label,
  value,
  emptyMessage = 'Chưa có nội dung để sao chép.',
}: {
  label: string
  value: () => string
  emptyMessage?: string
}) {
  const [state, setState] = useState<'idle' | 'copied' | 'empty'>('idle')
  const [manual, setManual] = useState('')
  async function copy() {
    const text = value()
    setManual('')
    if (!text) return setState('empty')
    try {
      await navigator.clipboard.writeText(text)
      setState('copied')
    } catch {
      setState('idle')
      setManual(text)
    }
  }
  return (
    <span className="inline-flex flex-col gap-2">
      <button type="button" className={outlinePill} onClick={copy}>
        {state === 'copied' ? <Check className="size-4" /> : <Copy className="size-4" />}
        {state === 'copied' ? 'Đã sao chép' : label}
      </button>
      {state === 'empty' && (
        <span role="alert" className="text-xs text-red-700 dark:text-red-300">
          {emptyMessage}
        </span>
      )}
      {manual && (
        <span role="alert" className="text-xs text-slate-600 dark:text-slate-400">
          Trình duyệt không cho sao chép tự động, hãy sao chép thủ công:{' '}
          <code className="break-all font-mono text-slate-900 dark:text-slate-100">{manual}</code>
        </span>
      )}
    </span>
  )
}
