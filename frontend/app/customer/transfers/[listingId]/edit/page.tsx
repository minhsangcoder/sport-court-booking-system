import { ListingForm } from '@/features/transfers/listing-form'
export default async function Page({params}:{params:Promise<{listingId:string}>}){const {listingId}=await params;return <ListingForm listingId={listingId}/>}
