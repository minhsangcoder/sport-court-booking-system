import { AdminAccountDetail } from '@/features/admin/account-detail'
export default async function Page({params}:{params:Promise<{userId:string}>}){const {userId}=await params;return <AdminAccountDetail id={userId}/>}
