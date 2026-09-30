'use client'

import { Bell, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function Header({ onLogin }: { onLogin?: () => void }) {
  return <header className="border-b border-slate-200 bg-white"><div className="mx-auto flex max-w-7xl items-center justify-between px-5 py-4"><div className="flex items-center gap-2"><span className="flex size-9 items-center justify-center rounded-lg bg-emerald-500 text-white"><Zap className="size-4 fill-current" /></span><span className="text-lg font-bold">Sport<span className="text-emerald-500">Hub</span></span></div><div className="flex items-center gap-2"><Button variant="ghost" size="icon" aria-label="Thông báo"><Bell className="size-4" /></Button><Button variant="outline" size="sm" onClick={onLogin}>Đăng nhập</Button></div></div></header>
}
