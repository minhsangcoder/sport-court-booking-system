import { AvailabilityPage } from '@/features/discovery/availability-page'
export default async function Page({params,searchParams}:{params:Promise<{facilityId:string;courtId:string}>;searchParams:Promise<{date?:string}>}){const {facilityId,courtId}=await params;const {date}=await searchParams;return <AvailabilityPage key={date} facilityId={facilityId} courtId={courtId} initialDate={date}/>}
