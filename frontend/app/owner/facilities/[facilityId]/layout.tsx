import { FacilityShell } from '@/features/facility/facility-context'
export default async function Layout({params,children}:{params:Promise<{facilityId:string}>;children:React.ReactNode}){
 const {facilityId}=await params
 return <FacilityShell id={facilityId}>{children}</FacilityShell>
}
