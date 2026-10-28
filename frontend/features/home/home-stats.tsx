const stats = [
  { value: '30′', label: 'Bước đặt linh hoạt' },
  { value: '60–240′', label: 'Thời lượng mỗi lượt' },
  { value: '2′', label: 'QR vào sân tự làm mới' },
  { value: '0%', label: 'Phí chuyển nhượng P2P' },
]
export function HomeStats() {
  return (
    <section className="mx-auto w-full max-w-7xl px-4 pb-20 pt-28 sm:px-6 lg:pt-32">
      <dl className="grid grid-cols-2 gap-y-8 lg:grid-cols-4 lg:divide-x lg:divide-slate-200 dark:lg:divide-slate-800">
        {stats.map((s) => (
          <div key={s.label} className="flex flex-col lg:px-8 lg:first:pl-0">
            <dd className="order-1 font-display text-5xl font-extrabold italic leading-none tracking-tight text-slate-900 sm:text-6xl dark:text-white">
              {s.value}
            </dd>
            <dt className="order-2 mt-3 text-sm text-slate-500 dark:text-slate-400">{s.label}</dt>
          </div>
        ))}
      </dl>
    </section>
  )
}
