import { GroupDetail } from '@/features/groups/group-detail'
export default async function Page({params}:{params:Promise<{groupId:string}>}){const {groupId}=await params;return <GroupDetail id={groupId}/>}
