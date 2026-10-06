'use client'
import { useState } from 'react'
import { useApi } from '@/lib/use-api'
import { Button } from '@/components/ui/button'
import { Failure } from './application-page'
import { legalFields } from './apply-page'
import { applicationLabels,type ApplicationDetail,type ApplicationHistoryEntry,type ApplicationHistoryPage } from './types'

const labels:Record<string,string>={
 OWNER_APPLICATION_CREATED:'Tạo hồ sơ đăng ký',OWNER_APPLICATION_UPDATED:'Chỉnh sửa thông tin chủ sân',
 OWNER_APPLICATION_SUBMITTED:'Gửi hồ sơ thẩm định',OWNER_APPLICATION_DECISION_REQUESTED:'Ghi nhận quyết định của Admin',
 OWNER_APPLICATION_SUPPLEMENT_REQUIRED:'Yêu cầu bổ sung hồ sơ',OWNER_APPLICATION_APPROVED:'Phê duyệt Owner và cơ sở đầu tiên',OWNER_APPLICATION_REJECTED:'Từ chối hồ sơ',
 FIRST_FACILITY_DRAFT_CREATED:'Tạo bản nháp cơ sở đầu tiên',FACILITY_UPDATED:'Chỉnh sửa cơ sở',COURT_CREATED:'Thêm sân',COURT_UPDATED:'Chỉnh sửa sân',
 FACILITY_SUBMITTED:'Gửi cơ sở thẩm định',ADMIN_FACILITY_APPROVE:'Chuẩn bị kích hoạt cơ sở',ADMIN_FACILITY_SUPPLEMENT_REQUIRED:'Mở cơ sở để bổ sung',ADMIN_FACILITY_REJECT:'Từ chối cơ sở',
 FACILITY_DOCUMENT_UPLOADED:'Thêm tài liệu cơ sở',FACILITY_DOCUMENT_ARCHIVED:'Lưu trữ tài liệu cũ',FACILITY_DOCUMENT_REMOVED:'Xóa tài liệu cơ sở',FACILITY_IMAGE_UPLOADED:'Thêm ảnh cơ sở',FACILITY_IMAGE_DELETED:'Xóa ảnh cơ sở'
}
function contactEdited(details?:string|null){try{return (JSON.parse(details||'{}') as {changedFields?:unknown[]}).changedFields?.includes('contactEmail')===true}catch{return false}}
function timestamp(value:string,timezone:string){return new Intl.DateTimeFormat('vi-VN',{dateStyle:'medium',timeStyle:'medium',timeZone:timezone}).format(new Date(value))}
function Event({entry,timezone}:{entry:ApplicationHistoryEntry;timezone:string}){
 const resubmitted=entry.action==='OWNER_APPLICATION_SUBMITTED'&&entry.submissionOrigin==='SUPPLEMENT_REQUIRED'
 return <li className="rounded-2xl border bg-white p-5" data-event-id={entry.id}>
  <h3 className="font-semibold">{resubmitted?'Gửi lại hồ sơ sau bổ sung':labels[entry.action]||'Cập nhật hồ sơ'}</h3>
  <p className="mt-2 text-sm text-slate-500"><time dateTime={entry.occurredAt}>{timestamp(entry.occurredAt,timezone)}</time></p>
  <p className="mt-2 break-words text-sm">Người thực hiện: <span className="font-medium">{entry.actorName||entry.actorId||'Không còn thông tin tài khoản'}</span></p>
  {(entry.fromState||entry.toState)&&<p className="mt-3 text-sm text-emerald-800">{entry.fromState&&entry.fromState!==entry.toState&&`${applicationLabels[entry.fromState]} → `}{entry.toState&&applicationLabels[entry.toState]}</p>}
  {entry.reason&&<p className="mt-3 whitespace-pre-wrap break-words rounded-lg bg-amber-50 p-3 text-sm">{entry.reason}</p>}
  {entry.changedFields.length>0&&<p className="mt-3 text-sm">Trường đã chỉnh sửa: {entry.changedFields.map(field=>field==='contactEmail'?'Email liên hệ cơ sở':legalFields.find(([key])=>key===field)?.[1]||field).join(', ')}.</p>}
  {entry.submissionId&&<a href={`#submission-${entry.submissionId}`} className="mt-3 inline-block text-sm text-emerald-700 underline">Xem bản hồ sơ đã gửi</a>}
  {!entry.metadataAvailable&&<p className="mt-3 text-xs text-slate-500">Bản ghi cũ chưa lưu trạng thái trước/sau hoặc danh sách trường thay đổi.</p>}
 </li>
}
export function RevisionHistory({detail}:{detail:ApplicationDetail}){
 const [page,setPage]=useState(0)
 const history=useApi<ApplicationHistoryPage>(`/admin/owner-applications/${detail.application.id}/history?page=${page}&size=20`)
 let timezone=detail.facility.facility.timezone
 try{new Intl.DateTimeFormat('vi-VN',{timeZone:timezone})}catch{timezone='UTC'}
 const facilityEvents=detail.facility.audit?.filter(e=>!e.action.endsWith('_VIEWED'))||[]
 return <section className="mt-6 min-w-0 space-y-5" aria-label="Lịch sử hồ sơ">
  <div><h2 className="text-xl font-bold">Diễn biến hồ sơ</h2><p className="mt-2 text-sm text-slate-500">Múi giờ: {timezone}{timezone!==detail.facility.facility.timezone?' (múi giờ cơ sở chưa hợp lệ)':''}. Nhật ký chỉ hiển thị tên trường chỉnh sửa, không hiển thị giá trị pháp lý cũ.</p></div>
  {history.loading&&<p role="status">Đang tải lịch sử…</p>}
  {history.error&&<Failure message={history.error} retry={history.reload}/>}
  {!history.loading&&!history.error&&history.data&&<>
   {history.data.items.length===0&&<p className="rounded-xl border border-dashed p-5 text-sm text-slate-500">Chưa có sự kiện trong trang lịch sử này.</p>}
   <ol className="space-y-3">{history.data.items.map(entry=><Event key={entry.id} entry={entry} timezone={timezone}/>)}</ol>
   <nav aria-label="Phân trang lịch sử" className="flex flex-wrap items-center justify-between gap-3"><p className="text-sm text-slate-500">{history.data.totalElements} sự kiện · Trang {Math.min(page+1,Math.max(1,history.data.totalPages))}/{Math.max(1,history.data.totalPages)}</p><div className="flex gap-2"><Button variant="outline" disabled={page===0} onClick={()=>setPage(p=>p-1)}>Trước</Button><Button variant="outline" disabled={page+1>=history.data.totalPages} onClick={()=>setPage(p=>p+1)}>Tiếp</Button></div></nav>
  </>}
  <h2 className="pt-4 text-xl font-bold">Các bản hồ sơ đã gửi</h2>
  {detail.history.length===0&&<p className="text-sm text-slate-500">Hồ sơ chưa gửi thẩm định.</p>}
  {detail.history.map((h,i)=>{
   const snapshot=h.facilitySnapshot
   return <article id={`submission-${h.id}`} key={h.id} className="scroll-mt-6 rounded-xl border p-5 text-sm"><h3 className="font-semibold">Lần {detail.history.length-i} · Đã gửi thẩm định</h3><p className="mt-2 text-slate-500"><time dateTime={h.submittedAt}>{timestamp(h.submittedAt,timezone)}</time></p><p className="mt-3 break-words">{snapshot.facility?.name||'Cơ sở đầu tiên'} · {snapshot.courts?.length||0} sân · {snapshot.documents?.length||0} tài liệu</p><p className="mt-3 min-w-0 [overflow-wrap:anywhere]">Email liên hệ cơ sở: {snapshot.facility?.contactEmail||'Chưa có trong phiên bản hồ sơ này'}</p><p className="mt-2 text-xs text-slate-500">Bản lưu tại thời điểm gửi; tài liệu gốc được giữ trong mục Tài liệu và ảnh.</p></article>
  })}
  <h2 className="pt-4 text-xl font-bold">Nhật ký cơ sở đầu tiên</h2><p className="text-xs text-slate-500">Từ tối đa 100 bản ghi gần nhất của cơ sở; lượt xem được ẩn. Cấu hình đã gửi được lưu trong từng bản hồ sơ.</p>
  {facilityEvents.length===0&&<p className="text-sm text-slate-500">Chưa có thay đổi cơ sở trong các bản ghi gần nhất.</p>}
  {facilityEvents.map(e=><article key={e.id} className="rounded-xl bg-slate-50 p-4 text-sm"><h3 className="font-medium">{labels[e.action]||'Cập nhật cơ sở'}</h3>{contactEdited(e.details)&&<p className="mt-2 text-sm">Trường đã chỉnh sửa: Email liên hệ cơ sở.</p>}<p className="mt-2 text-xs text-slate-500"><time dateTime={e.occurred_at}>{timestamp(e.occurred_at,timezone)}</time></p></article>)}
 </section>
}
