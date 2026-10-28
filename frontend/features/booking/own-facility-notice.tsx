import Link from 'next/link'

export function OwnFacilityNotice({ facilityId }: { facilityId: string }) {
  return (
    <aside
      role="status"
      className="my-4 space-y-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950"
    >
      <p>
        Đây là cơ sở của bạn. Để đặt sân cho khách, hãy dùng Đặt tại quầy; không tạo lượt đặt cá
        nhân tại cơ sở mình.
      </p>
      <div className="flex flex-wrap gap-4 font-semibold">
        <Link className="underline" href={`/owner/counter-booking?facilityId=${facilityId}`}>
          Đặt tại quầy cho khách
        </Link>
        <Link className="underline" href={`/owner/bookings?facilityId=${facilityId}`}>
          Lịch đặt & Check-in khách
        </Link>
      </div>
    </aside>
  )
}
