import { AdminFacilityReview } from '@/features/admin/facility-review'
export default async function Page({params}:{params:Promise<{facilityId:string}>}){const {facilityId}=await params;return <AdminFacilityReview id={facilityId}/>}
