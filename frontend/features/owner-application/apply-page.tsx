'use client'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { RequireAuth } from '@/features/auth/require-auth'
import { useAuth } from '@/lib/auth-context'
import { api,ApiError } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { type OwnerApplication } from './types'
import { LegalFields,legalFrom } from './legal-fields'
import { FacilityFields,facilityFrom } from '@/features/facility/facility-fields'
import type { FacilityLocation } from '@/features/facility/location-picker'
export function OwnerApplyPage(){return <RequireAuth roles={['CUSTOMER']}><Create/></RequireAuth>}
function Create(){
 const {user}=useAuth();const router=useRouter();const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [location,setLocation]=useState<FacilityLocation>(null)
 if(user?.roles.includes('OWNER'))return <div className="mx-auto max-w-3xl p-8"><h1 className="text-3xl font-bold">Bạn đã có quyền Owner</h1><Link href="/owner/facilities" className="mt-5 block text-emerald-700 underline">Quản lý cơ sở của tôi</Link></div>
 async function submit(e:React.FormEvent<HTMLFormElement>){e.preventDefault();if(busy)return;setBusy(true);setError('');const f=new FormData(e.currentTarget);if(!location){setError('Chọn vị trí cơ sở trên bản đồ.');setBusy(false);return}try{
  const application=await api<OwnerApplication>('/owner-applications',{method:'POST',body:JSON.stringify({legal:legalFrom(f),facility:facilityFrom(f,location,'facilityName')})});router.push(`/owner/application/${application.id}`)
 }catch(err){setError(err instanceof ApiError?err.message+(err.fieldErrors.length?' '+err.fieldErrors.map(f=>f.message).join('; '):''):err instanceof Error?err.message:'Không thể tạo hồ sơ.')}finally{setBusy(false)}}
 return <main className="mx-auto max-w-4xl px-5 py-10"><p className="text-sm font-bold text-emerald-700">TRỞ THÀNH ĐỐI TÁC SPORTHUB</p><h1 className="mt-3 text-3xl font-black">Đăng ký trở thành Owner</h1><p className="mt-4 text-slate-600">Tạo hồ sơ nháp, sau đó khai báo sân, lịch mở cửa, bảng giá và tài liệu cho cơ sở đầu tiên trước khi gửi Admin thẩm định.</p><Link href="/owner/application" className="mt-4 inline-block text-sm text-emerald-700 underline">Xem hồ sơ đã đăng ký</Link>
 <form onSubmit={submit} className="mt-8 space-y-8"><fieldset className="grid min-w-0 grid-cols-1 gap-5 rounded-2xl border bg-white p-6 md:grid-cols-2"><legend className="px-2 text-xl font-bold">Người đại diện và thông tin pháp lý</legend><LegalFields busy={busy}/><p className="text-xs text-slate-500 md:col-span-2">Giấy tờ định danh và ngân hàng chỉ dành cho người đăng ký và Admin thẩm định. Không nhập dữ liệu thật trong bản demo.</p></fieldset>
 <fieldset className="grid min-w-0 grid-cols-1 gap-5 rounded-2xl border p-6 md:grid-cols-2"><legend className="px-2 text-xl font-bold">Cơ sở đầu tiên</legend><FacilityFields nameField="facilityName" location={location} onLocationChange={setLocation} busy={busy}/></fieldset>
 {error&&<p role="alert" className="text-red-700">{error}</p>}<Button type="submit" disabled={busy} className="bg-emerald-600 text-white">{busy?'Đang tạo hồ sơ…':'Tạo hồ sơ và tiếp tục'}</Button></form></main>
}
