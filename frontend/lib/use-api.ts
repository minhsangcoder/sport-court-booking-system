'use client'
import { useCallback,useEffect, useState } from 'react'
import { api } from './api'
export function useApi<T>(path: string | null) {
  const [epoch,setEpoch]=useState(0)
  const [result,setResult]=useState<{path:string;epoch:number;data:T|null;error:string}|null>(null)
  useEffect(()=>{
    if (!path) return
    let active=true
    void api<T>(path).then(data=>{if(active)setResult({path,epoch,data,error:''})})
      .catch(error=>{if(active)setResult({path,epoch,data:null,error:error instanceof Error?error.message:'Không thể tải dữ liệu.'})})
    return()=>{active=false}
  },[path,epoch])
  const current=result?.path===path && result.epoch===epoch
  const reload=useCallback(()=>setEpoch(value=>value+1),[])
  return {data:result?.path===path?result.data:null,error:current?result.error:'',loading:Boolean(path)&&!current,reload}
}
