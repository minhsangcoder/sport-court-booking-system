export type Hours = { id:string;facilityId:string;courtId:string|null;dayOfWeek:number;opensAt:string;closesAt:string;slotMinutes:number;version:number }
export type ScheduleException = { id:string;courtId:string|null;date:string;type:'CLOSED'|'SPECIAL_HOURS';opensAt:string|null;closesAt:string|null;reason:string }
export type PriceRule = { id:string;courtId:string|null;sportCategoryId:string|null;dayOfWeek:number|null;specificDate:string|null;startsAt:string;endsAt:string;pricePerSlot:number;priority:number;label:string;effectiveFrom:string;effectiveTo:string|null;currency:string;active:boolean;version:number }
export type Slot = { startsAt:string;endsAt:string;state:string;reason:string|null;amount:number|null;currency:string;ruleId:string|null;ruleVersion:number;ruleLabel:string|null }
export type Preview = { facilityId:string;courtId:string;date:string;timezone:string;slots:Slot[] }
export type Quote = { facilityId:string;courtId:string;startsAt:string;endsAt:string;amount:number;currency:string;timezone:string;segments:Slot[] }
export const weekdays = ['Thứ hai','Thứ ba','Thứ tư','Thứ năm','Thứ sáu','Thứ bảy','Chủ nhật']
export function money(value:number,currency='VND'){return new Intl.NumberFormat('vi-VN',{style:'currency',currency}).format(value)}
