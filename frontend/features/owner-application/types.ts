import type { Facility,Court } from '@/features/facility/types'
import type { FacilityReview } from '@/features/facility/review-page'
export type ApplicationState='DRAFT'|'SUBMITTING'|'PENDING_APPROVAL'|'SUPPLEMENT_REQUIRED'|'DECIDING'|'APPROVING'|'APPROVED'|'REJECTED'
export type OwnerApplication={id:string;userId:string;facilityId:string;businessName:string;facilityName:string;state:ApplicationState;submittedAt:string|null;reason:string|null;commissionPercent:number|null;lastError:string|null}
export type OwnerApplicationPage={items:OwnerApplication[];page:number;size:number;totalElements:number;totalPages:number}
export type Legal={representativeName:string;identityNumber:string;businessName:string;businessLicense:string|null;taxCode:string|null;bankName:string;bankAccountHolder:string;bankAccountNumber:string}
export type ApplicationDetail={application:OwnerApplication;legal:Legal;facility:{facility:Facility;courts:Court[];reviews:FacilityReview[]};history:{id:string;submittedAt:string;facilitySnapshot:unknown}[];audit:{id:string;actorId:string;action:string;createdAt:string;details:unknown}[]}
export const applicationLabels:Record<ApplicationState,string>={DRAFT:'Đang chuẩn bị',SUBMITTING:'Đang gửi hồ sơ',PENDING_APPROVAL:'Chờ thẩm định',SUPPLEMENT_REQUIRED:'Cần bổ sung',DECIDING:'Đang lưu quyết định',APPROVING:'Đang kích hoạt Owner',APPROVED:'Đã phê duyệt',REJECTED:'Đã từ chối'}
export const isEditable=(state:ApplicationState)=>state==='DRAFT'||state==='SUPPLEMENT_REQUIRED'
