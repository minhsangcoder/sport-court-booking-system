export const ownerApplicationListPath='/admin/owner-applications'
export const filterKeys=['q','state','applicant','facility','submittedFrom','submittedTo','reviewedFrom','reviewedTo','reviewedBy','applicationId','sort','page','size'] as const
export type ApplicationFilters=Partial<Record<typeof filterKeys[number],string>>

export function applicationFilters(params:Record<string,string|string[]|undefined>):ApplicationFilters {
  const result:ApplicationFilters={}
  for(const key of filterKeys){const value=params[key];if(typeof value==='string')result[key]=value.trim()}
  return result
}
export function applicationQuery(filters:ApplicationFilters){
  const params=new URLSearchParams()
  for(const key of filterKeys){const value=filters[key];if(value!==undefined&&(value!==''||key==='state'))params.set(key,value)}
  return params.toString()
}
export function applicationListHref(filters:ApplicationFilters){const q=applicationQuery(filters);return ownerApplicationListPath+(q?`?${q}`:'')}
export function safeApplicationReturn(value:unknown){
  if(typeof value!=='string'||!(value===ownerApplicationListPath||value.startsWith(ownerApplicationListPath+'?')))return ownerApplicationListPath
  const url=new URL(value,'http://sporthub.invalid')
  if(url.pathname!==ownerApplicationListPath)return ownerApplicationListPath
  return applicationListHref(applicationFilters(Object.fromEntries(url.searchParams)))
}
