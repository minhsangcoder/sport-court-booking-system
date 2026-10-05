import { ListingForm } from '@/features/transfers/listing-form'
export default async function Page({params}:{params:Promise<{bookingId:string}>}){const {bookingId}=await params;return <ListingForm bookingId={bookingId}/>}
