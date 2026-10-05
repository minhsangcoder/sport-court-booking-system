import { SearchPage } from '@/features/discovery/search-page'
export default async function Page({searchParams}:{searchParams:Promise<Record<string,string|string[]|undefined>>}){
 const params=await searchParams
 const initial=Object.fromEntries(Object.entries(params).filter((entry):entry is [string,string]=>typeof entry[1]==='string'))
 const today=new Date().toLocaleDateString('en-CA',{timeZone:'Asia/Ho_Chi_Minh'})
 return <SearchPage key={JSON.stringify(initial)} initial={initial} today={today}/>
}
