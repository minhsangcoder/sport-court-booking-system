import { BookingDetailPage } from '@/features/booking/booking-detail'
export default async function Page({params}:{params:Promise<{bookingId:string}>}){const {bookingId}=await params;return <BookingDetailPage id={bookingId}/>}
