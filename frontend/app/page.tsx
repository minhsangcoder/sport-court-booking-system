import Image from 'next/image'
import Link from 'next/link'
export default function Home() {
  return <div><section className="relative overflow-hidden bg-emerald-950 text-white">
    <Image src="/images/hero-pickleball.png" alt="Sân thể thao" fill priority className="object-cover opacity-25" />
    <div className="relative mx-auto max-w-7xl px-6 py-24 md:py-36"><p className="mb-5 text-sm font-bold uppercase tracking-[0.25em] text-amber-300">Hẹn nhau ra sân</p>
      <h1 className="max-w-3xl text-5xl font-black leading-tight tracking-tight md:text-7xl">Một lịch chơi.<br />Nhiều kết nối.</h1>
      <p className="mt-7 max-w-lg text-lg leading-relaxed text-emerald-100">Tìm sân phù hợp, chọn lịch và quản lý buổi chơi của bạn trên SportHub.</p>
      <div className="mt-9 flex flex-wrap gap-4"><Link className="rounded-full bg-amber-400 px-7 py-3 font-bold text-emerald-950" href="/search">Khám phá sân</Link><Link className="rounded-full border border-white/40 px-7 py-3 font-semibold" href="/register">Tham gia SportHub</Link></div>
    </div></section>
    <section className="mx-auto grid max-w-7xl gap-8 px-6 py-16 md:grid-cols-3">{[
      ['Tìm lịch phù hợp','Khám phá cơ sở và xem các khung giờ có thể đặt.'],
      ['Quản lý lịch chơi','Theo dõi booking và thanh toán trong tài khoản của bạn.'],
      ['Vận hành cơ sở','Quản lý sân, lịch mở cửa và bảng giá trong không gian riêng.'],
    ].map(([title,text]) => <div key={title} className="rounded-2xl border p-7"><h2 className="text-xl font-bold text-emerald-900">{title}</h2><p className="mt-3 leading-relaxed text-slate-600">{text}</p></div>)}</section>
  </div>
}