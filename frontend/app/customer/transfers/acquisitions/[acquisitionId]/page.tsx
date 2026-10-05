import { AcquisitionPage } from '@/features/transfers/acquisition-page'
export default async function Page({params}:{params:Promise<{acquisitionId:string}>}){const {acquisitionId}=await params;return <AcquisitionPage id={acquisitionId}/>}
