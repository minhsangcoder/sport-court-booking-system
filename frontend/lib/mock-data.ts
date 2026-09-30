export const hanoiVenues = [
  { id: 'venue-1', name: 'Hoàng Mai Sports', address: '24 Hoàng Mai, Hà Nội', sports: ['Cầu lông', 'Pickleball'] },
  { id: 'venue-2', name: 'The Yard Pickleball', address: '10 Trần Thái Tông, Hà Nội', sports: ['Pickleball'] },
  { id: 'venue-3', name: 'Hanoi Club Tennis', address: '76 Yên Phụ, Hà Nội', sports: ['Tennis'] },
] as const

export const courtSlots = [
  { label: '05:00', type: 'normal', price: 120000 }, { label: '06:00', type: 'booking', price: 180000 },
  { label: '07:00', type: 'peak', price: 250000 }, { label: '08:00', type: 'normal', price: 180000 },
  { label: '09:00', type: 'maintenance', price: 0 }, { label: '10:00', type: 'normal', price: 150000 },
  { label: '11:00', type: 'closed', price: 0 }, { label: '12:00', type: 'normal', price: 150000 },
  { label: '13:00', type: 'normal', price: 150000 }, { label: '14:00', type: 'booking', price: 180000 },
  { label: '15:00', type: 'normal', price: 180000 }, { label: '16:00', type: 'peak', price: 250000 },
  { label: '17:00', type: 'peak', price: 280000 }, { label: '18:00', type: 'booking', price: 300000 },
  { label: '19:00', type: 'booking', price: 300000 }, { label: '20:00', type: 'normal', price: 250000 },
] as const

export const transferListings = [
  { id: 1, sport: 'Cầu lông', court: 'Sân Cầu Lông Hồ Đền Lừ - Sân 03', venue: '24 Hoàng Mai, Hà Nội', original: 300000, transfer: 200000, status: 'active' },
  { id: 2, sport: 'Pickleball', court: 'The Yard Pickleball - Court 05', venue: '10 Trần Thái Tông, Hà Nội', original: 360000, transfer: 250000, status: 'active' },
  { id: 3, sport: 'Tennis', court: 'Hanoi Club Tennis - Sân 02', venue: '76 Yên Phụ, Hà Nội', original: 500000, transfer: 350000, status: 'locked' },
] as const

export const bookings = [
  { id: 'BK-2026-8891', customer: 'Trần Minh Anh', court: 'Sân Pickleball Hoàng Mai 01', time: '18:00 – 20:00', amount: 320000, payment: 'VNPay' },
  { id: 'BK-240629-18', customer: 'Nguyễn Minh Anh', court: 'Sân Cầu lông 03', time: '19:00 – 21:00', amount: 450000, payment: 'Tiền mặt' },
] as const

export const formatVnd = (value: number) => new Intl.NumberFormat('vi-VN').format(value) + ' VNĐ'
