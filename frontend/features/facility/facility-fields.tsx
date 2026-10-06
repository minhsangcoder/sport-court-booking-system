'use client'
import dynamic from 'next/dynamic'
import { Input } from '@/components/ui/input'
import type { FacilityProfile,FacilityInput } from './types'
import type { FacilityLocation } from './location-picker'
const LocationPicker=dynamic(()=>import('./location-picker').then(module=>module.LocationPicker),{ssr:false,loading:()=> <p role="status">Đang mở bản đồ…</p>})
export function facilityFrom(form:FormData,location:FacilityLocation,nameField='name'):FacilityInput {
 const text=(name:string)=>String(form.get(name)??'').trim()
 return {name:text(nameField),phone:text('phone'),contactEmail:text('contactEmail')||null,addressLine:text('addressLine'),province:text('province'),district:text('district'),ward:text('ward'),description:text('description'),timezone:text('timezone'),amenities:text('amenities').split(',').map(x=>x.trim()).filter(Boolean),...(location??{})}
}
export function FacilityFields({facility,location,onLocationChange,busy=false,frozen=false,fields={},nameField='name'}:{facility?:FacilityProfile;location:FacilityLocation;onLocationChange:(value:FacilityLocation)=>void;busy?:boolean;frozen?:boolean;fields?:Record<string,string>;nameField?:string}){
 function field(name:keyof FacilityInput,label:string,max:number,required=true,type='text'){return <label className="grid min-w-0 gap-2 text-sm font-medium">{label}<Input type={type} name={name==='name'?nameField:name} defaultValue={String(facility?.[name]??(name==='timezone'?'Asia/Ho_Chi_Minh':''))} maxLength={max} required={required} disabled={busy} aria-invalid={!!fields[name]}/>{fields[name]&&<span className="text-xs text-red-700">{fields[name]}</span>}</label>}
 return <>{field('name','Tên cơ sở',180)}{field('phone','Số điện thoại cơ sở',30)}{field('contactEmail','Email liên hệ cơ sở (tùy chọn)',254,false,'email')}{field('addressLine','Địa chỉ',500)}{field('province','Tỉnh / thành phố',100)}{field('district','Quận / huyện',100)}{field('ward','Phường / xã',100)}{field('timezone','Múi giờ IANA',80)}
 <label className="grid min-w-0 gap-2 text-sm font-medium">Tiện ích, phân cách bằng dấu phẩy<Input name="amenities" defaultValue={facility?.amenities.join(', ')} maxLength={1200} disabled={busy}/></label>
 <label className="grid min-w-0 gap-2 text-sm md:col-span-2">Mô tả<textarea className="min-h-28 rounded-lg border p-3" name="description" defaultValue={facility?.description} maxLength={5000} disabled={busy}/></label>
 <LocationPicker value={location} onChange={onLocationChange} disabled={busy||frozen}/>
 {(fields.latitude||fields.longitude||fields.locationComplete)&&<p role="alert" className="text-sm text-red-700 md:col-span-2">{fields.latitude||fields.longitude||fields.locationComplete}</p>}</>
}
