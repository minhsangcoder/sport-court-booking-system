'use client'
import { LegalFields } from '@/features/owner-application/legal-fields'
import { FacilityFields } from '@/features/facility/facility-fields'
import { DocumentFileField } from '@/features/facility/document-file-field'
import type { FacilityLocation } from '@/features/facility/location-picker'
import type { Category } from '@/features/facility/types'
import { useApi } from '@/lib/use-api'
import { Input } from '@/components/ui/input'
export function OwnerSignupFields({location,onLocationChange,busy,fields}:{location:FacilityLocation;onLocationChange:(value:FacilityLocation)=>void;busy:boolean;fields:Record<string,string>}){
 const categories=useApi<Category[]>('/sport-categories')
 return <div className="grid min-w-0 gap-7">
 <section className="grid min-w-0 gap-5 md:grid-cols-2"><h2 className="text-xl font-bold md:col-span-2">Thông tin đăng ký chủ sân</h2><LegalFields busy={busy} fields={fields}/><p className="text-xs text-slate-500 md:col-span-2">Thông tin pháp lý chỉ dành cho bạn và Admin thẩm định. Chỉ được cấp quyền Owner sau phê duyệt.</p></section>
 <section className="grid min-w-0 gap-5 border-t pt-6 md:grid-cols-2"><h2 className="text-xl font-bold md:col-span-2">Cơ sở đầu tiên và vị trí</h2><FacilityFields nameField="facilityName" location={location} onLocationChange={onLocationChange} busy={busy} fields={fields}/></section>
 <section className="grid min-w-0 gap-5 border-t pt-6 md:grid-cols-2"><h2 className="text-xl font-bold md:col-span-2">Sân và cấu hình hoạt động ban đầu</h2><p className="text-sm text-slate-500 md:col-span-2">Cấu hình một sân và giá cho các ngày mở cửa để gửi hồ sơ hiện tại. Bạn có thể bổ sung sân sau khi được phê duyệt.</p>
 <label className="grid min-w-0 gap-2 text-sm font-medium">Mã sân<Input name="courtCode" maxLength={50} required disabled={busy}/></label><label className="grid min-w-0 gap-2 text-sm font-medium">Tên sân<Input name="courtName" maxLength={180} required disabled={busy}/></label>
 <label className="grid min-w-0 gap-2 text-sm font-medium">Bộ môn<select className="h-10 w-full min-w-0 rounded-lg border px-3" name="sportCategoryId" required disabled={busy||categories.loading} defaultValue=""><option value="">Chọn bộ môn</option>{categories.data?.filter(c=>c.active).map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
 {categories.loading&&<p role="status">Đang tải bộ môn…</p>}{categories.error&&<p role="alert" className="text-sm text-red-700">{categories.error}<button type="button" onClick={categories.reload} className="ml-2 underline">Thử lại</button></p>}
 <label className="grid min-w-0 gap-2 text-sm font-medium">Giá mỗi slot (VND)<Input type="number" name="pricePerSlot" required min="0.01" step="0.01" max="9999999999.99" disabled={busy}/></label>
 <label className="grid min-w-0 gap-2 text-sm font-medium">Giờ mở cửa<Input name="opensAt" type="time" defaultValue="06:00" required disabled={busy}/></label><label className="grid min-w-0 gap-2 text-sm font-medium">Giờ đóng cửa<Input name="closesAt" type="time" defaultValue="22:00" required disabled={busy}/></label>
 <label className="grid min-w-0 gap-2 text-sm font-medium">Độ dài slot (phút)<Input name="slotMinutes" type="number" min={5} max={720} defaultValue={60} required disabled={busy}/></label>
 <fieldset className="flex min-w-0 flex-wrap gap-4 md:col-span-2"><legend className="mb-3 text-sm font-medium">Các ngày mở cửa</legend>{['Thứ 2','Thứ 3','Thứ 4','Thứ 5','Thứ 6','Thứ 7','Chủ nhật'].map((day,i)=><label key={day} className="flex items-center gap-2 text-sm"><input type="checkbox" name="days" value={i+1} defaultChecked disabled={busy}/>{day}</label>)}</fieldset></section>
 <section className="grid min-w-0 gap-5 border-t pt-6"><h2 className="text-xl font-bold">Tài liệu xác minh và ảnh</h2><DocumentFileField label="Giấy tờ định danh" error={fields.identityDocument} name="identityDocument" disabled={busy}/><DocumentFileField label="Giấy tờ địa điểm / sử dụng mặt bằng" error={fields.locationDocument} name="locationDocument" disabled={busy}/><DocumentFileField label="Ảnh giới thiệu cơ sở" error={fields.facilityImage} name="facilityImage" image disabled={busy}/></section>
 </div>
}
