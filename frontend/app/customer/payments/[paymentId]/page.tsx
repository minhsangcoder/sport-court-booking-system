import { PaymentPage } from '@/features/booking/payment-page'
export default async function Page({params}:{params:Promise<{paymentId:string}>}){const {paymentId}=await params;return <PaymentPage id={paymentId}/>}
