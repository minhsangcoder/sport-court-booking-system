import { AdminPaymentDetail } from '@/features/admin/payment-monitor'
export default async function Page({params}:{params:Promise<{paymentId:string}>}){const {paymentId}=await params;return <AdminPaymentDetail id={paymentId}/>}
