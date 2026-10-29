'use client'
import { calendarLabel, demoVnd, type ReportDay } from '@/features/facility/demo-report'
import { series } from '@/features/facility/report/chart-series.data'
export function DailyChart({ days }: { days: ReportDay[] }) {
  const max = Math.max(1, ...days.flatMap((day) => series.map((item) => Number(day[item.key]))))
  const x = (index: number) => 64 + (index * 620) / Math.max(1, days.length - 1)
  const y = (amount: string) => 176 - (Number(amount) / max) * 136
  return (
    <div>
      <div className="mb-4 flex flex-wrap gap-5 text-sm">
        {series.map((item) => (
          <span key={item.key} className="inline-flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: item.color }} />
            {item.label}
          </span>
        ))}
      </div>
      <div className="overflow-x-auto">
        <svg
          role="img"
          aria-label="Tiền sân và phí cơ sở theo ngày"
          viewBox="0 0 720 216"
          className="min-w-[500px] w-full"
        >
          {[0, 0.5, 1].map((fraction) => (
            <g key={fraction}>
              <line
                x1="64"
                x2="684"
                y1={176 - 136 * fraction}
                y2={176 - 136 * fraction}
                stroke="#e2e8f0"
              />
              <text x="56" y={180 - 136 * fraction} textAnchor="end" fontSize="10" fill="#64748b">
                {Math.round(max * fraction).toLocaleString('vi-VN')}
              </text>
            </g>
          ))}
          {series.map((item) => (
            <g key={item.key}>
              <polyline
                points={days.map((day, index) => `${x(index)},${y(day[item.key])}`).join(' ')}
                fill="none"
                stroke={item.color}
                strokeWidth="2.5"
              />
              {days.map((day, index) => (
                <circle key={day.date} cx={x(index)} cy={y(day[item.key])} r="3" fill={item.color}>
                  <title>
                    {calendarLabel(day.date)} · {item.label}: {demoVnd(day[item.key])}
                  </title>
                </circle>
              ))}
            </g>
          ))}
          {days.length > 0 && (
            <>
              <text x="64" y="202" fontSize="11" fill="#64748b">
                {calendarLabel(days[0].date)}
              </text>
              <text x="684" y="202" textAnchor="end" fontSize="11" fill="#64748b">
                {calendarLabel(days[days.length - 1].date)}
              </text>
            </>
          )}
        </svg>
      </div>
      <details className="mt-3 text-sm">
        <summary className="cursor-pointer text-emerald-700">Xem số liệu theo ngày (VND)</summary>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr>
                <th className="p-2">Ngày</th>
                {series.map((item) => (
                  <th className="p-2" key={item.key}>
                    {item.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {days.map((day) => (
                <tr key={day.date} className="border-t">
                  <td className="p-2">{calendarLabel(day.date)}</td>
                  {series.map((item) => (
                    <td className="p-2" key={item.key}>
                      {demoVnd(day[item.key])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  )
}
