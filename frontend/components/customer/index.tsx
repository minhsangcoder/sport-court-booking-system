'use client'

import { MapPin, Users } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatVnd, transferListings } from '@/lib/mock-data'

export function CourtCard({ name, address, sport, onSelect }: { name: string; address: string; sport: string; onSelect?: () => void }) {
  return <Card><CardContent className="p-5"><Badge>{sport}</Badge><h3 className="mt-3 font-bold">{name}</h3><p className="mt-1 flex items-center gap-1 text-sm text-slate-500"><MapPin className="size-3" />{address}</p><Button className="mt-4 w-full" onClick={onSelect}>Chọn sân</Button></CardContent></Card>
}

export function TimeSlotMatrix({ slots }: { slots: readonly { label: string; type: string; price: number }[] }) {
  return <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">{slots.map((slot) => <button key={slot.label} className="rounded-lg border border-slate-200 px-3 py-2 text-left text-sm hover:border-emerald-400"><span className="font-semibold">{slot.label}</span><span className="mt-1 block text-xs text-slate-500">{slot.price ? formatVnd(slot.price) : slot.type}</span></button>)}</div>
}

export function FilterBar() {
  return <div className="flex flex-wrap gap-2"><Input className="max-w-xs" placeholder="Tìm sân, khu vực..." /><Button variant="outline">Tất cả môn</Button><Button variant="outline">Gần tôi</Button></div>
}

export function GroupBookingSplit() {
  return <Button variant="outline" className="gap-2"><Users className="size-4" />Chia tiền nhóm</Button>
}

export function MarketplaceListing() {
  return <div className="grid gap-4 md:grid-cols-3">{transferListings.map((listing) => <Card key={listing.id}><CardContent className="p-5"><Badge>{listing.sport}</Badge><h3 className="mt-3 font-semibold">{listing.court}</h3><p className="mt-1 text-sm text-slate-500">{listing.venue}</p><div className="mt-4 flex items-center justify-between"><span className="text-sm text-slate-400 line-through">{formatVnd(listing.original)}</span><span className="font-bold text-emerald-600">{formatVnd(listing.transfer)}</span></div></CardContent></Card>)}</div>
}
