import { ListingDetail } from '@/features/transfers/listing-detail'
export default async function Page({params}:{params:Promise<{listingId:string}>}){const {listingId}=await params;return <ListingDetail id={listingId}/>}
