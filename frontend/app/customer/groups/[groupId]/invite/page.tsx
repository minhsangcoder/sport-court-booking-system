import { InvitePage } from '@/features/groups/invite-page'
export default async function Page({params}:{params:Promise<{groupId:string}>}){const {groupId}=await params;return <InvitePage id={groupId}/>}
