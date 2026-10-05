import { PublicFacilityDetail } from '@/features/discovery/facility-detail'
export default async function Page({params}:{params:Promise<{facilityId:string}>}){const {facilityId}=await params;return <PublicFacilityDetail id={facilityId}/>}
