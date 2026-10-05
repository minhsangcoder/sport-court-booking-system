'use client'
import { useEffect,useState } from 'react'
export function Countdown({expiresAt}:{expiresAt:string}){
 const remaining=useRemaining(expiresAt)
 return <p role="timer" className={`rounded-lg px-4 py-3 text-sm font-semibold ${remaining===0?'bg-red-50 text-red-700':'bg-amber-50 text-amber-800'}`}>{remaining===null?'Đang kiểm tra thời hạn…':remaining===0?'Giữ chỗ đã hết hạn':`Thời gian giữ chỗ: ${Math.floor(remaining/60)}:${String(remaining%60).padStart(2,'0')}`}</p>
}
export function useRemaining(expiresAt?:string){
 const [now,setNow]=useState<number|null>(null)
 useEffect(()=>{const tick=()=>setNow(Date.now());const first=window.setTimeout(tick,0);const timer=window.setInterval(tick,1000);return()=>{clearTimeout(first);clearInterval(timer)}},[])
 return now===null||!expiresAt?null:Math.max(0,Math.floor((new Date(expiresAt).getTime()-now)/1000))
}
