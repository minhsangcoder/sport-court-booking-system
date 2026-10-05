import { AvailabilityPage } from '@/features/discovery/availability-page'
export default async function Page({params}:{params:Promise<{facilityId:string;courtId:string}>}){const {facilityId,courtId}=await params;return <AvailabilityPage facilityId={facilityId} courtId={courtId}/>}
