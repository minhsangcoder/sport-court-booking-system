import type { FacilityProfile,Court } from '@/features/facility/types'
import type { FacilityReview } from '@/features/facility/review-page'
export type ApplicationState='DRAFT'|'SUBMITTING'|'PENDING_APPROVAL'|'SUPPLEMENT_REQUIRED'|'DECIDING'|'APPROVING'|'APPROVED'|'REJECTED'
export type OwnerApplication={id:string;userId:string;facilityId:string;businessName:string;facilityName:string;state:ApplicationState;submittedAt:string|null;reason:string|null;commissionPercent:number|null;lastError:string|null}
export type OwnerApplicationPage={items:OwnerApplication[];page:number;size:number;totalElements:number;totalPages:number}
export type Legal={representativeName:string;identityNumber:string;businessName:string;businessLicense:string|null;taxCode:string|null;bankName:string;bankAccountHolder:string;bankAccountNumber:string}
export type ApplicationHistoryEntry={id:string;actorId:string|null;actorName:string|null;action:string;occurredAt:string;fromState:ApplicationState|null;toState:ApplicationState|null;reason:string|null;changedFields:(keyof Legal|'contactEmail')[];submissionId:string|null;submissionOrigin:ApplicationState|null;metadataAvailable:boolean}
export type ApplicationHistoryPage={items:ApplicationHistoryEntry[];page:number;size:number;totalElements:number;totalPages:number}
export type ApplicationDetail={application:OwnerApplication;legal:Legal;applicant?:{id:string;fullName:string;email:string|null;phone:string|null;status:string}|null;facility:{facility:FacilityProfile;courts:Court[];reviews:FacilityReview[];audit?:{id:string;actor_id:string;action:string;occurred_at:string;details?:string|null}[]};history:{id:string;submittedAt:string;facilitySnapshot:FacilitySnapshot}[];reviewSnapshot?:FacilitySnapshot|null;audit:{id:string;actorId:string;action:string;createdAt:string;details:unknown}[]}
export const applicationLabels:Record<ApplicationState,string>={DRAFT:'Đang chuẩn bị',SUBMITTING:'Đang gửi hồ sơ',PENDING_APPROVAL:'Chờ thẩm định',SUPPLEMENT_REQUIRED:'Cần bổ sung',DECIDING:'Đang lưu quyết định',APPROVING:'Đang kích hoạt Owner',APPROVED:'Đã phê duyệt',REJECTED:'Đã từ chối'}
export const isEditable=(state:ApplicationState)=>state==='DRAFT'||state==='SUPPLEMENT_REQUIRED'

export type FacilitySnapshot={facility?:Partial<FacilityProfile>;courts?:unknown[];documents?:unknown[];hours?:unknown[];prices?:unknown[]}
