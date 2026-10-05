import { OwnApplication } from '@/features/owner-application/application-page'
export default async function Page({params}:{params:Promise<{applicationId:string}>}){const {applicationId}=await params;return <OwnApplication id={applicationId}/>}
