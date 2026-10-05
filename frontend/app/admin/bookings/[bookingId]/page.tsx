import { AdminBookingDetail } from '@/features/admin/booking-monitor'
export default async function Page({params}:{params:Promise<{bookingId:string}>}){const {bookingId}=await params;return <AdminBookingDetail id={bookingId}/>}
