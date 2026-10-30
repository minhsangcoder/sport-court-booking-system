'use client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { provinces } from '@/features/facility/provinces.data'
import type { Category } from '@/features/facility/types'
import { useAuth } from '@/lib/auth-context'
import { useApi } from '@/lib/use-api'
import { SlidersHorizontal } from 'lucide-react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useEffect, useState } from 'react'
import { PageBanner } from '@/components/page-banner'
import { CourtCard } from './court-card'
import type { DiscoveryResult } from './types'

type FormerDistrict = { name: string; wards: { code: string; name: string }[] }
const SearchMap = dynamic(() => import('./search-map').then((module) => module.SearchMap), {
  ssr: false,
  loading: () => (
    <p role="status" className="mt-6">
      Đang mở bản đồ…
    </p>
  ),
})
const fields = [
  'q',
  'sportCategoryId',
  'provinceCode',
  'district',
  'ward',
  'date',
  'opensAfter',
  'closesBefore',
  'minPrice',
  'maxPrice',
  'latitude',
  'longitude',
  'radiusKm',
  'sort',
]
const selectStyle =
  'h-9 w-full rounded-lg border border-slate-300 bg-white px-3 text-[13px] font-medium text-slate-900 focus:border-emerald-600 focus:outline-none focus:ring-2 focus:ring-emerald-600/20'
export function SearchPage({ initial, today }: { initial: Record<string, string>; today: string }) {
  const router = useRouter()
  const { user, ready } = useAuth()

  useEffect(() => {
    if (
      ready &&
      user &&
      user.roles.includes('STAFF') &&
      !user.roles.includes('OWNER') &&
      !user.roles.includes('ADMIN')
    ) {
      router.replace('/staff/bookings')
    }
  }, [user, ready, router])

  const filters = Object.fromEntries(
    fields.map((name) => [
      name,
      initial[name] ?? (name === 'date' ? today : name === 'sort' ? 'NAME' : ''),
    ]),
  )
  const mapView = initial.view === 'map'
  const pastDate = Boolean(/^\d{4}-\d{2}-\d{2}$/.test(filters.date) && filters.date < today)
  if (pastDate) filters.date = today
  const [categoryId, setCategoryId] = useState(filters.sportCategoryId)
  const [provinceCode, setProvinceCode] = useState(filters.provinceCode)
  const [district, setDistrict] = useState(filters.district)
  const [ward, setWard] = useState(filters.ward)
  const [provinceWards, setProvinceWards] = useState<{ code: string; name: string }[]>([])
  const districts = useApi<FormerDistrict[]>(
    provinceCode ? `/facilities/areas?provinceCode=${provinceCode}` : null,
  )
  const currentDistrict = provinceCode ? districts.data?.find((d) => d.name === district) : undefined
  const wardOptions = currentDistrict?.wards ?? (provinceCode ? provinceWards : [])
  useEffect(() => {
    if (!provinceCode) return
    let active = true
    // The nationwide ward list is large, so it loads only once a province is chosen.
    import('@/features/facility/administrative-divisions').then(({ provinceByCode }) => {
      if (active) setProvinceWards(provinceByCode(provinceCode)?.wards ?? [])
    })
    return () => {
      active = false
    }
  }, [provinceCode])
  const [position, setPosition] = useState({
    latitude: filters.latitude,
    longitude: filters.longitude,
  })
  const [locating, setLocating] = useState(false)
  const [locationMessage, setLocationMessage] = useState('')
  function locate() {
    if (!navigator.geolocation) {
      setLocationMessage('Trình duyệt không hỗ trợ vị trí. Bạn vẫn có thể tìm theo khu vực.')
      return
    }
    setLocating(true)
    setLocationMessage('')
    navigator.geolocation.getCurrentPosition(
      (value) => {
        setPosition({
          latitude: String(value.coords.latitude),
          longitude: String(value.coords.longitude),
        })
        setLocationMessage('Đã chọn vị trí hiện tại làm điểm tìm kiếm.')
        setLocating(false)
      },
      () => {
        setLocationMessage('Không thể lấy vị trí. Hãy tìm theo khu vực hoặc nhập điểm tìm kiếm.')
        setLocating(false)
      },
      { timeout: 10000, maximumAge: 60000 },
    )
  }
  const params = new URLSearchParams(Object.entries(filters).filter(([, value]) => value !== ''))
  const resource = useApi<{ items: DiscoveryResult[]; truncated: boolean }>(
    `/bookings/search?${params}`,
  )
  const categories = useApi<Category[]>('/sport-categories')
  function search(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const query = new URLSearchParams()
    for (const name of fields) {
      const value = String(form.get(name) ?? '').trim()
      if (value) query.set(name, value)
    }
    if (mapView) query.set('view', 'map')
    router.push(`/search?${query}`)
  }
  function viewHref(map: boolean) {
    const query = new URLSearchParams(params)
    if (map) query.set('view', 'map')
    return `/search?${query}`
  }
  const time = (value: string, zone: string) =>
    new Date(value).toLocaleTimeString('vi-VN', {
      timeZone: zone,
      hour: '2-digit',
      minute: '2-digit',
    })
  return (
    <>
      <PageBanner
        eyebrow="Lịch chơi bắt đầu tại đây"
        title="Tìm sân cho buổi chơi tiếp theo"
        description="Chọn bộ môn và ngày chơi để xem sân còn trống cùng mức giá thực tế."
        icon="pickleball"
      >
        <div className="rounded-2xl border border-white/20 bg-slate-950/60 p-4 shadow-2xl backdrop-blur-xl sm:p-5">
          <form onSubmit={search}>
            <div className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-[1.4fr_1fr_1fr_auto]">
              <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                Tên cơ sở hoặc khu vực
                <Input
                  name="q"
                  placeholder="Tên sân, phường/xã, quận/huyện…"
                  maxLength={180}
                  className="h-9 bg-white text-slate-900 border-slate-300"
                  defaultValue={filters.q}
                />
              </label>
              <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                Bộ môn
                <select
                  name="sportCategoryId"
                  aria-label="Bộ môn"
                  value={categoryId}
                  onChange={(event) => setCategoryId(event.target.value)}
                  className={selectStyle}
                >
                  <option value="">Tất cả bộ môn</option>
                  {categories.data
                    ?.filter((c) => c.active)
                    .map((c) => (
                      <option value={c.id} key={c.id}>
                        {c.name}
                      </option>
                    ))}
                </select>
              </label>
              <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                Ngày chơi
                <Input
                  name="date"
                  type="date"
                  defaultValue={filters.date}
                  min={today}
                  required
                  className="h-9 bg-white text-slate-900 border-slate-300"
                />
              </label>
              <Button
                type="submit"
                className="h-9 bg-emerald-600 px-5 text-white hover:bg-emerald-500"
              >
                Tìm sân
              </Button>
            </div>
            <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-slate-800/80 pt-3.5">
              <details
                className="w-full"
                open={Boolean(
                  initial.provinceCode ||
                  initial.district ||
                  initial.ward ||
                  initial.opensAfter ||
                  initial.minPrice ||
                  initial.latitude,
                )}
              >
                <summary className="inline-flex cursor-pointer items-center gap-1.5 text-xs font-medium text-slate-300 hover:text-white">
                  <SlidersHorizontal size={14} strokeWidth={1.75} className="text-emerald-400" />
                  Bộ lọc khu vực, giờ và giá
                </summary>
                <div className="mt-3.5 grid gap-3.5 rounded-lg border border-slate-800 bg-slate-900/90 p-4 sm:grid-cols-2 lg:grid-cols-3">
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Tỉnh / thành phố
                    <select
                      name="provinceCode"
                      value={provinceCode}
                      onChange={(event) => {
                        setProvinceCode(event.target.value)
                        setProvinceWards([])
                        setDistrict('')
                        setWard('')
                      }}
                      className={selectStyle}
                    >
                      <option value="">Tất cả tỉnh / thành phố</option>
                      {provinces.map((province) => (
                        <option key={province.code} value={province.code}>
                          {province.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Quận / huyện (trước sáp nhập)
                    <select
                      name="district"
                      value={district}
                      disabled={!districts.data?.length}
                      onChange={(event) => {
                        setDistrict(event.target.value)
                        setWard('')
                      }}
                      className={`${selectStyle} disabled:bg-slate-200 disabled:text-slate-500`}
                    >
                      <option value="">
                        {!provinceCode
                          ? 'Chọn tỉnh / thành phố trước'
                          : districts.data?.length
                            ? 'Tất cả quận / huyện'
                            : 'Chưa có dữ liệu quận / huyện'}
                      </option>
                      {districts.data?.map((item) => (
                        <option key={item.name} value={item.name}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Phường / xã
                    <select
                      name="ward"
                      value={ward}
                      disabled={!wardOptions.length}
                      onChange={(event) => setWard(event.target.value)}
                      className={`${selectStyle} disabled:bg-slate-200 disabled:text-slate-500`}
                    >
                      <option value="">
                        {provinceCode ? 'Tất cả phường / xã' : 'Chọn tỉnh / thành phố trước'}
                      </option>
                      {ward && !wardOptions.some((item) => item.name === ward) && (
                        <option value={ward}>{ward}</option>
                      )}
                      {wardOptions.map((item) => (
                        <option key={item.code} value={item.name}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Sắp xếp
                    <select name="sort" defaultValue={filters.sort} className={selectStyle}>
                      <option value="NAME">Tên cơ sở</option>
                      <option value="PRICE_ASC">Giá thấp đến cao</option>
                      <option value="PRICE_DESC">Giá cao đến thấp</option>
                      <option value="DISTANCE">Khoảng cách gần nhất</option>
                    </select>
                  </label>
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Khung giờ bắt đầu từ
                    <Input
                      name="opensAfter"
                      type="time"
                      defaultValue={filters.opensAfter}
                      className="bg-white text-slate-900"
                    />
                  </label>
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Khung giờ kết thúc trước
                    <Input
                      name="closesBefore"
                      type="time"
                      defaultValue={filters.closesBefore}
                      className="bg-white text-slate-900"
                    />
                  </label>
                  <div className="grid grid-cols-2 gap-2.5">
                    <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                      Giá tối thiểu / h
                      <Input
                        name="minPrice"
                        type="number"
                        min={0}
                        step={1}
                        defaultValue={filters.minPrice}
                        className="bg-white text-slate-900"
                      />
                    </label>
                    <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                      Giá tối đa / h
                      <Input
                        name="maxPrice"
                        type="number"
                        min={0}
                        step={1}
                        defaultValue={filters.maxPrice}
                        className="bg-white text-slate-900"
                      />
                    </label>
                  </div>
                  <div className="sm:col-span-2 lg:col-span-3">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={locating}
                      onClick={locate}
                      className="text-slate-900"
                    >
                      {locating ? 'Đang xác định vị trí…' : 'Dùng vị trí hiện tại'}
                    </Button>
                    {locationMessage && (
                      <p role="status" className="mt-2 text-xs text-slate-300">
                        {locationMessage}
                      </p>
                    )}
                  </div>
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Vĩ độ điểm tìm kiếm
                    <Input
                      name="latitude"
                      type="number"
                      min={-90}
                      max={90}
                      step="any"
                      placeholder="21.028"
                      value={position.latitude}
                      onChange={(e) =>
                        setPosition((current) => ({ ...current, latitude: e.target.value }))
                      }
                      className="bg-white text-slate-900"
                    />
                  </label>
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Kinh độ điểm tìm kiếm
                    <Input
                      name="longitude"
                      type="number"
                      min={-180}
                      max={180}
                      step="any"
                      placeholder="105.78"
                      value={position.longitude}
                      onChange={(e) =>
                        setPosition((current) => ({ ...current, longitude: e.target.value }))
                      }
                      className="bg-white text-slate-900"
                    />
                  </label>
                  <label className="grid gap-1.5 text-xs font-medium text-slate-300">
                    Bán kính (km)
                    <Input
                      name="radiusKm"
                      type="number"
                      min={0.1}
                      max={500}
                      step="any"
                      defaultValue={filters.radiusKm}
                      className="bg-white text-slate-900"
                    />
                  </label>
                  <p className="text-[11px] leading-relaxed text-slate-400 sm:col-span-2 lg:col-span-3">
                    Giá áp dụng theo giờ (/h). Lịch và khung giờ theo múi giờ của cơ sở. Nhập cả vĩ
                    độ và kinh độ khi tìm theo khoảng cách.
                  </p>
                </div>
              </details>
              <Link
                href="/search"
                className="mt-2 inline-block text-xs font-medium text-slate-400 hover:text-white underline underline-offset-4"
              >
                Đặt lại bộ lọc
              </Link>
            </div>
          </form>
        </div>
      </PageBanner>
      <main className="mx-auto max-w-6xl px-4 sm:px-6 py-6 sm:py-8">
        {categories.error && (
          <p role="alert" className="mt-4 text-xs text-red-700">
            Không thể tải bộ môn.{' '}
            <button onClick={categories.reload} className="underline">
              Thử lại
            </button>
          </p>
        )}
        {pastDate && (
          <p role="status" className="mt-4 text-xs text-amber-700">
            Ngày tìm kiếm đã qua. Đã chuyển sang ngày hôm nay.
          </p>
        )}
        <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 pb-3">
          <div>
            <h2 className="text-base sm:text-lg font-bold text-slate-900">
              {filters.q ? `Kết quả cho “${filters.q}”` : 'Sân còn trống cho ngày đã chọn'}
            </h2>
            {resource.data && (
              <p className="mt-0.5 text-xs text-slate-500">
                {resource.data.items.length} sân · {filters.date}
              </p>
            )}
          </div>
          <nav
            aria-label="Cách xem kết quả"
            className="flex w-fit gap-1 rounded-lg border border-slate-200 bg-slate-100 p-0.5"
          >
            <Link
              href={viewHref(false)}
              scroll={false}
              aria-current={!mapView ? 'page' : undefined}
              className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${!mapView ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600 hover:text-slate-900'}`}
            >
              Danh sách
            </Link>
            <Link
              href={viewHref(true)}
              scroll={false}
              aria-current={mapView ? 'page' : undefined}
              className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors ${mapView ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600 hover:text-slate-900'}`}
            >
              Bản đồ
            </Link>
          </nav>
        </div>
        {resource.loading && (
          <p role="status" className="mt-6 text-xs text-slate-500">
            Đang kiểm tra sân, giá và lịch trống…
          </p>
        )}
        {resource.error && (
          <p
            role="alert"
            className="mt-6 rounded-lg border border-red-200 bg-red-50 p-3.5 text-xs text-red-700"
          >
            {resource.error}
            <button className="ml-3 font-semibold underline" onClick={resource.reload}>
              Thử lại
            </button>
          </p>
        )}
        {resource.data?.truncated && (
          <p className="mt-4 text-xs text-amber-700">
            Đang hiển thị tối đa 100 kết quả. Hãy thêm khu vực hoặc bộ môn để thu hẹp tìm kiếm.
          </p>
        )}
        {resource.data?.items.length === 0 && (
          <div className="my-6 rounded-xl border border-slate-200 bg-white p-6">
            <h3 className="text-sm font-semibold text-slate-900">Chưa tìm thấy sân phù hợp</h3>
            <p className="mt-1 text-xs text-slate-500">
              Thử mở rộng khu vực, khoảng giá hoặc chọn ngày và khung giờ khác.
            </p>
          </div>
        )}
        {mapView && resource.data && resource.data.items.length > 0 && (
          <SearchMap results={resource.data.items} />
        )}
        {!mapView && (
          <div className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {resource.data?.items.map((result) => (
              <CourtCard key={result.courtId} result={result} time={time} />
            ))}
          </div>
        )}
      </main>
    </>
  )
}
