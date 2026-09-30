'use client'

import { QrCode, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export function QRCodeModal({ bookingCode, onClose }: { bookingCode: string; onClose: () => void }) { return <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4"><Card className="w-full max-w-sm text-center"><CardHeader><div className="flex items-center justify-between"><CardTitle>Vé điện tử QR</CardTitle><Button variant="ghost" size="icon" onClick={onClose} aria-label="Đóng"><X /></Button></div></CardHeader><CardContent><QrCode className="mx-auto size-40" /><p className="mt-3 font-mono">{bookingCode}</p></CardContent></Card></div> }

export function BookingDetailSheet({ bookingCode, onClose }: { bookingCode: string; onClose: () => void }) { return <Card><CardHeader><CardTitle>Chi tiết booking</CardTitle></CardHeader><CardContent><p className="font-mono">{bookingCode}</p><Button className="mt-4" onClick={onClose}>Đóng</Button></CardContent></Card> }

export function AuthModal({ onClose }: { onClose: () => void }) { return <Card><CardHeader><CardTitle>Xác thực tài khoản</CardTitle></CardHeader><CardContent><Button onClick={onClose}>Đóng</Button></CardContent></Card> }
