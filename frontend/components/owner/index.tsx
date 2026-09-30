'use client'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatVnd } from '@/lib/mock-data'

export function OwnerScheduleGrid({ slots = [] }: { slots?: readonly { label: string; type: string; price: number }[] }) { return <Card><CardHeader><CardTitle>Lịch vận hành sân</CardTitle></CardHeader><CardContent><div className="grid grid-cols-4 gap-2">{slots.slice(0, 12).map((slot) => <div key={slot.label} className="rounded border p-2 text-center text-xs"><strong>{slot.label}</strong><span className="mt-1 block text-slate-500">{slot.type}</span></div>)}</div></CardContent></Card> }

export function StaffManagement({ onAdd }: { onAdd?: () => void }) { return <Card><CardHeader><div className="flex items-center justify-between"><CardTitle>Quản lý nhân viên</CardTitle><Button onClick={onAdd}>+ Thêm nhân viên</Button></div></CardHeader><CardContent><Input placeholder="Tìm theo tên hoặc số điện thoại" /></CardContent></Card> }

export function RevenueAnalytics() { return <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{[['Tổng doanh thu tháng', 128500000], ['Doanh thu trực tuyến', 95000000], ['Tiền mặt tại quầy', 33500000]].map(([label, amount]) => <Card key={String(label)}><CardContent className="p-4"><p className="text-xs text-slate-500">{label}</p><p className="mt-1 text-2xl font-bold">{formatVnd(Number(amount))}</p></CardContent></Card>)}<Card><CardContent className="p-4"><p className="text-xs text-slate-500">Tỷ lệ lấp đầy sân</p><p className="mt-1 text-2xl font-bold">76,4%</p></CardContent></Card></div> }

export function MaintenanceDialog({ onConfirm }: { onConfirm?: () => void }) { return <Card><CardHeader><CardTitle>Thiết lập bảo trì sân</CardTitle></CardHeader><CardContent><Button onClick={onConfirm}>Xác nhận khóa bảo trì</Button></CardContent></Card> }
