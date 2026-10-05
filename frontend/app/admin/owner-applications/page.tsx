import { AdminOwnerApplications } from '@/features/owner-application/admin-pages'
import { applicationFilters } from '@/features/owner-application/search'
export default async function Page({searchParams}:{searchParams:Promise<Record<string,string|string[]|undefined>>}){
 const initial=applicationFilters(await searchParams)
 return <AdminOwnerApplications key={JSON.stringify(initial)} initial={initial}/>
}
