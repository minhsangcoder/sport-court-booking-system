import { CourtEditor } from '@/features/facility/courts'
export default async function Page({params}:{params:Promise<{courtId:string}>}){const {courtId}=await params;return <CourtEditor courtId={courtId}/>}
