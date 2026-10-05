import { AdminOwnerApplication } from '@/features/owner-application/admin-pages'
export default async function Page({params}:{params:Promise<{applicationId:string}>}){const {applicationId}=await params;return <AdminOwnerApplication id={applicationId}/>}
