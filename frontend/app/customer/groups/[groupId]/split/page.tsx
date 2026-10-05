import { SplitPage } from '@/features/groups/split-page'
export default async function Page({params}:{params:Promise<{groupId:string}>}){const {groupId}=await params;return <SplitPage id={groupId}/>}
