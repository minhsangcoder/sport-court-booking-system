import { AdminOwnerApplication } from '@/features/owner-application/admin-pages'
import { safeApplicationReturn } from '@/features/owner-application/search'
export default async function Page({params,searchParams}:{params:Promise<{applicationId:string}>;searchParams:Promise<Record<string,string|string[]|undefined>>}){
 const {applicationId}=await params
 return <AdminOwnerApplication id={applicationId} returnTo={safeApplicationReturn((await searchParams).returnTo)}/>
}
