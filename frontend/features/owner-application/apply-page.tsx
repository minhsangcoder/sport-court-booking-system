'use client'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { RequireAuth } from '@/features/auth/require-auth'
import { useAuth } from '@/lib/auth-context'
import { api,ApiError } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { type Legal,type OwnerApplication } from './types'
export const legalFields:[keyof Legal,string,number,boolean][]=[['representativeName','Họ tên người đại diện',180,true],['identityNumber','Số CCCD / giấy tờ định danh',50,true],['businessName','Tên hộ kinh doanh / doanh nghiệp',180,true],['businessLicense','Số giấy phép kinh doanh',100,false],['taxCode','Mã số thuế',50,false],['bankName','Ngân hàng nhận thanh toán',100,true],['bankAccountHolder','Chủ tài khoản ngân hàng',100,true],['bankAccountNumber','Số tài khoản ngân hàng',50,true]]
export function legalFrom(form:FormData):Legal{return Object.fromEntries(legalFields.map(([key,,,required])=>[key,String(form.get(key)||'').trim()||(required?'':null)])) as Legal}
export function LegalFields({legal,busy=false}:{legal?:Legal;busy?:boolean}){return <>{legalFields.map(([name,label,max,required])=><label key={name} className="grid gap-2 text-sm font-medium">{label}{!required&&' (tùy chọn)'}<Input name={name} defaultValue={legal?.[name]??''} maxLength={max} required={required} disabled={busy} autoComplete="off"/></label>)}</>}
export function OwnerApplyPage(){return <RequireAuth roles={['CUSTOMER']}><Create/></RequireAuth>}
function Create(){
 const {user}=useAuth();const router=useRouter();const [busy,setBusy]=useState(false);const [error,setError]=useState('')
 if(user?.roles.includes('OWNER'))return <div className="mx-auto max-w-3xl p-8"><h1 className="text-3xl font-bold">Bạn đã có quyền Owner</h1><Link href="/owner/facilities" className="mt-5 block text-emerald-700 underline">Quản lý cơ sở của tôi</Link></div>
 async function submit(e:React.FormEvent<HTMLFormElement>){e.preventDefault();if(busy)return;setBusy(true);setError('');const f=new FormData(e.currentTarget),value=(key:string)=>String(f.get(key)||'').trim();try{
  const application=await api<OwnerApplication>('/owner-applications',{method:'POST',body:JSON.stringify({legal:legalFrom(f),facility:{name:value('facilityName'),phone:value('phone'),addressLine:value('addressLine'),province:value('province'),district:value('district'),ward:value('ward'),description:value('description'),timezone:'Asia/Ho_Chi_Minh',latitude:Number(value('latitude')),longitude:Number(value('longitude')),amenities:[]}})});router.push(`/owner/application/${application.id}`)
 }catch(err){setError(err instanceof ApiError?err.message+(err.fieldErrors.length?' '+err.fieldErrors.map(f=>f.message).join('; '):''):err instanceof Error?err.message:'Không thể tạo hồ sơ.')}finally{setBusy(false)}}
 return <main className="mx-auto max-w-4xl px-5 py-10"><p className="text-sm font-bold text-emerald-700">TRỞ THÀNH ĐỐI TÁC SPORTHUB</p><h1 className="mt-3 text-3xl font-black">Đăng ký trở thành Owner</h1><p className="mt-4 text-slate-600">Tạo hồ sơ nháp, sau đó khai báo sân, lịch mở cửa, bảng giá và tài liệu cho cơ sở đầu tiên trước khi gửi Admin thẩm định.</p><Link href="/owner/application" className="mt-4 inline-block text-sm text-emerald-700 underline">Xem hồ sơ đã đăng ký</Link>
 <form onSubmit={submit} className="mt-8 space-y-8"><fieldset className="grid gap-5 rounded-2xl border bg-white p-6 md:grid-cols-2"><legend className="px-2 text-xl font-bold">Người đại diện và thông tin pháp lý</legend><LegalFields busy={busy}/><p className="text-xs text-slate-500 md:col-span-2">Giấy tờ định danh và ngân hàng chỉ dành cho người đăng ký và Admin thẩm định. Không nhập dữ liệu thật trong bản demo.</p></fieldset>
 <fieldset className="grid gap-5 rounded-2xl border p-6 md:grid-cols-2"><legend className="px-2 text-xl font-bold">Cơ sở đầu tiên</legend>{[['facilityName','Tên cơ sở',180],['phone','Số điện thoại cơ sở',30],['addressLine','Địa chỉ',500],['province','Tỉnh / thành phố',100],['district','Quận / huyện',100],['ward','Phường / xã',100]].map(([name,label,max])=><label key={name} className="grid gap-2 text-sm font-medium">{label}<Input name={String(name)} maxLength={Number(max)} required disabled={busy}/></label>)}{[['latitude','Vĩ độ',-90,90],['longitude','Kinh độ',-180,180]].map(([name,label,min,max])=><label key={name} className="grid gap-2 text-sm font-medium">{label}<Input type="number" name={String(name)} min={Number(min)} max={Number(max)} step="any" required disabled={busy}/></label>)}<label className="grid gap-2 text-sm md:col-span-2">Giới thiệu cơ sở<textarea name="description" maxLength={5000} disabled={busy} className="min-h-24 rounded-lg border p-3"/></label></fieldset>
 {error&&<p role="alert" className="text-red-700">{error}</p>}<Button type="submit" disabled={busy} className="bg-emerald-600 text-white">{busy?'Đang tạo hồ sơ…':'Tạo hồ sơ và tiếp tục'}</Button></form></main>
}
