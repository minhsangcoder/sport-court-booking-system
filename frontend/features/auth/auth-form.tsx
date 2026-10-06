'use client'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import { useRef,useState } from 'react'
import { OwnerSignupFields } from './owner-signup-fields'
import { legalFrom } from '@/features/owner-application/legal-fields'
import { facilityFrom } from '@/features/facility/facility-fields'
import { validateSignupFiles } from '@/features/facility/document-file-field'
import type { FacilityLocation } from '@/features/facility/location-picker'
import { authApi, ApiError } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
type Mode = 'login' | 'register' | 'verify' | 'forgot' | 'reset'
const titles = { login: 'Chào mừng trở lại', register: 'Tạo tài khoản SportHub', verify: 'Xác minh tài khoản', forgot: 'Quên mật khẩu', reset: 'Đặt lại mật khẩu' }
export function AuthForm({ mode }: { mode: Mode }) {
  const router = useRouter(); const params = useSearchParams()
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [success, setSuccess] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [applyAsOwner,setApplyAsOwner]=useState(false);const [ownerVisited,setOwnerVisited]=useState(false);const [location,setLocation]=useState<FacilityLocation>(null)
  const signupRetry=useRef<{fingerprint:string;key:string}|null>(null)
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy) return
    setBusy(true); setError(''); setSuccess(''); setFieldErrors({})
    const values = new FormData(event.currentTarget)
    const value = (name: string) => String(values.get(name) ?? '').trim()
    try {
      if (mode === 'login') {
        await authApi.login(value('identifier'), String(values.get('password') ?? ''))
        const next=params.get('next')
        router.push(next?.startsWith('/')&&!next.startsWith('//')?next:'/customer/profile')
      } else if (mode === 'register') {
        const contact = value('identifier')
        const account={fullName:value('fullName'),password:String(values.get('password')??''),...(contact.includes('@')?{email:contact}:{phone:contact})}
        let result
        if(applyAsOwner){
          if(!location)throw new Error('Chọn vị trí cơ sở trên bản đồ trước khi gửi.')
          try{validateSignupFiles(values)}catch(fileError){const message=fileError instanceof Error?fileError.message:'File không hợp lệ';const name=message.startsWith('Giấy tờ định danh')?'identityDocument':message.startsWith('Giấy tờ địa điểm')?'locationDocument':'facilityImage';setFieldErrors({[name]:message});throw fileError}
          const setup={courtCode:value('courtCode'),courtName:value('courtName'),sportCategoryId:value('sportCategoryId'),days:values.getAll('days').map(Number),opensAt:value('opensAt'),closesAt:value('closesAt'),slotMinutes:Number(value('slotMinutes')),pricePerSlot:Number(value('pricePerSlot'))}
          if(!setup.days.length)throw new Error('Chọn ít nhất một ngày mở cửa.')
          if(setup.opensAt>=setup.closesAt)throw new Error('Giờ mở cửa phải trước giờ đóng cửa.')
          const body={...account,applyAsOwner:true,ownerApplication:{legal:legalFrom(values),facility:facilityFrom(values,location,'facilityName'),setup}}
          // The same in-memory request keeps its key after a timeout. Changed data gets a new key.
          const fingerprint=JSON.stringify(body)+['identityDocument','locationDocument','facilityImage'].map(name=>{const f=values.get(name) as File;return `${f.name}:${f.size}:${f.lastModified}`}).join('|')
          if(signupRetry.current?.fingerprint!==fingerprint)signupRetry.current={fingerprint,key:crypto.randomUUID()}
          const multipart=new FormData();multipart.set('request',new Blob([JSON.stringify(body)],{type:'application/json'}));for(const name of ['identityDocument','locationDocument','facilityImage'])multipart.set(name,values.get(name) as File)
          result=await authApi.registerOwner(multipart,signupRetry.current!.key)
        }else result=await authApi.register(account)
        const query=new URLSearchParams({challengeId:result.verificationChallengeId})
        if(result.ownerApplication){query.set('ownerApplicationId',result.ownerApplication.id);query.set('ownerState',result.ownerApplication.state)}
        if(result.accountStatus==='ACTIVE')router.push(result.ownerApplication?`/login?next=${encodeURIComponent(`/owner/application/${result.ownerApplication.id}`)}`:'/login')
        else router.push(`/verify?${query}`)

      } else if (mode === 'verify') {
        await authApi.verify(params.get('token') ? { verificationToken: params.get('token')! }
          : { challengeId: value('challengeId'), code: value('code') })
        setSuccess('Xác minh thành công. Bạn có thể đăng nhập.');
      } else if (mode === 'forgot') {
        await authApi.forgot(value('identifier'))
        setSuccess('Nếu tài khoản hợp lệ, hướng dẫn đã được gửi. Ở local, xem email hoặc SMS demo trong Mailpit.')
      } else {
        await authApi.reset({ challengeId: value('challengeId'), code: value('code'), newPassword: String(values.get('password') ?? '') })
        setSuccess('Mật khẩu đã được cập nhật và các phiên cũ đã thu hồi. Hãy đăng nhập lại.')
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Có lỗi xảy ra.')
      if (err instanceof ApiError) setFieldErrors(Object.fromEntries(err.fieldErrors.map(item => {const field=item.field.replace(/^ownerApplication\.(legal|facility|setup)\./,'');return [['email','phone'].includes(item.field)?'identifier':field,item.message]})))
    } finally { setBusy(false) }
  }
  function field(name: string, label: string, props: React.InputHTMLAttributes<HTMLInputElement> = {}) {
    return <label className="grid gap-2 text-sm font-medium">{label}<Input name={name} required disabled={busy} {...props} aria-invalid={Boolean(fieldErrors[name])} />
      {fieldErrors[name] && <span className="text-xs text-red-700">{fieldErrors[name]}</span>}</label>
  }
  return <Card className={`mx-auto min-w-0 w-full ${mode==='register'&&applyAsOwner?'max-w-4xl':'max-w-md'} shadow-sm`}><CardHeader><CardTitle className="text-2xl">{titles[mode]}</CardTitle>
    <p className="text-sm text-slate-500">Đặt sân, quản lý cơ sở và theo dõi lịch chơi của bạn.</p></CardHeader>
    <CardContent><form onSubmit={submit} className="grid gap-5">
      {mode === 'register' && field('fullName', 'Họ và tên', { minLength: 2, maxLength: 120, autoComplete: 'name' })}
      {['login','register','forgot'].includes(mode) && field('identifier', 'Email hoặc số điện thoại quốc tế', { autoComplete: 'username', placeholder: 'ban@example.com hoặc +84901234567' })}
      {['login','register','reset'].includes(mode) && field('password', mode === 'reset' ? 'Mật khẩu mới' : 'Mật khẩu', { type: 'password', minLength: mode === 'login' ? 1 : 8, maxLength: 72, autoComplete: mode === 'login' ? 'current-password' : 'new-password' })}
      {mode==='register'&&<><label className="flex items-start gap-3 rounded-xl border bg-emerald-50 p-4 text-sm font-medium"><input type="checkbox" className="mt-1" checked={applyAsOwner} disabled={busy} onChange={e=>{setApplyAsOwner(e.target.checked);if(e.target.checked)setOwnerVisited(true)}}/>Đăng ký trở thành chủ sân</label>{ownerVisited&&<fieldset hidden={!applyAsOwner} disabled={!applyAsOwner||busy} className="min-w-0"><OwnerSignupFields location={location} onLocationChange={setLocation} busy={busy||!applyAsOwner} fields={fieldErrors}/></fieldset>}</>}
      {mode==='verify'&&params.get('ownerApplicationId')&&<p role="status" className="rounded-lg bg-emerald-50 p-4 text-sm text-emerald-900">Tài khoản đã được tạo, chưa có quyền Owner. {params.get('ownerState')==='DRAFT'?'Hồ sơ cơ sở đang là nháp; sau xác minh và đăng nhập, hãy hoàn thiện hồ sơ trong mục đăng ký chủ sân.':params.get('ownerState')==='SUBMITTING'?'Hồ sơ đang được gửi lại an toàn.':'Hồ sơ cơ sở đầu tiên đã gửi để Admin thẩm định.'} Xác minh liên hệ để đăng nhập và theo dõi hồ sơ.</p>}
      {['verify','reset'].includes(mode) && !params.get('token') && <>
        {field('challengeId', 'Mã yêu cầu xác minh', { defaultValue: params.get('challengeId') ?? '', readOnly: Boolean(params.get('challengeId')) })}
        {field('code', 'Mã xác minh 6 chữ số', { pattern: '[0-9]{6}', inputMode: 'numeric', maxLength: 6, autoComplete: 'one-time-code' })}
        <p className="text-xs text-slate-500">Mã và liên kết được gửi qua kênh liên hệ.{process.env.NEXT_PUBLIC_DEMO_MAILPIT_URL&&<> Local/demo: mở <a className="underline" href={process.env.NEXT_PUBLIC_DEMO_MAILPIT_URL} target="_blank" rel="noreferrer">Mailpit</a>.</>}</p>
      </>}
      {error && <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-700">{error}</p>}
      {success && <p role="status" className="rounded-lg bg-emerald-50 p-3 text-sm text-emerald-800">{success} <Link className="underline" href={mode==='verify'&&/^[0-9a-f-]{36}$/.test(params.get('ownerApplicationId')??'')?`/login?next=${encodeURIComponent(`/owner/application/${params.get('ownerApplicationId')}`)}`:'/login'}>Đăng nhập</Link></p>}
      <Button className="h-11 bg-emerald-600 text-white hover:bg-emerald-700" type="submit" disabled={busy}>{busy ? 'Đang xử lý…' : mode === 'login' ? 'Đăng nhập' : mode === 'register' ? 'Tạo tài khoản' : mode === 'verify' ? 'Xác minh' : 'Tiếp tục'}</Button>
      <div className="flex flex-wrap justify-between gap-3 text-sm text-emerald-700"><Link href={mode === 'login' ? '/register' : '/login'}>{mode === 'login' ? 'Tạo tài khoản' : 'Quay lại đăng nhập'}</Link><Link href="/forgot-password">Quên mật khẩu?</Link></div>
    </form></CardContent></Card>
}
