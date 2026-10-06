export type Facility = {id:string;ownerId:string;name:string;phone:string;addressLine:string;province:string;district:string;ward:string;description?:string;timezone:string;latitude?:number;longitude?:number;status:string;amenities:string[];version:number}
export type FacilityProfile = Facility & {contactEmail?:string|null}
export type Category = {id:string;name:string;active:boolean}
export type Court = {id:string;facilityId:string;sportCategoryId:string;code:string;name:string;description?:string;enabled:boolean;version:number}
export type FacilityInput = Omit<FacilityProfile,'id'|'ownerId'|'status'|'version'>
export type Media = {id:string;courtId?:string;objectKey:string;url:string;contentType:string;sizeBytes:number}
export type Maintenance = {id:string;courtId:string;startsAt:string;endsAt:string;reason:string;cancelled:boolean}
