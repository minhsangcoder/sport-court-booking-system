'use client'
import { Input } from '@/components/ui/input'
import dynamic from 'next/dynamic'
import { useState } from 'react'
import { AddressAutocomplete, type FacilityPlaceSelection } from './address-autocomplete'
import { provinceByCode, provinceByName, provinces, wardByCode } from './administrative-divisions'
import type { FacilityLocation } from './location-picker'
import type { FacilityInput, FacilityProfile } from './types'
type FacilityAddress = { addressLine: string; provinceCode: string; wardCode: string }
const LocationPicker = dynamic(
  () => import('./location-picker').then((module) => module.LocationPicker),
  { ssr: false, loading: () => <p role="status">Đang mở bản đồ…</p> },
)
export function FacilityFields({
  facility,
  location,
  onLocationChange,
  busy = false,
  frozen = false,
  fields = {},
  nameField = 'name',
}: {
  facility?: FacilityProfile
  location: FacilityLocation
  onLocationChange: (value: FacilityLocation) => void
  busy?: boolean
  frozen?: boolean
  fields?: Record<string, string>
  nameField?: string
}) {
  const [focusLocation, setFocusLocation] = useState<FacilityLocation>(null)
  const [address, setAddress] = useState<FacilityAddress>(() => {
    const province = facility?.provinceCode
      ? provinceByCode(facility.provinceCode)
      : provinceByName(facility?.province)
    const ward = wardByCode(province?.code, facility?.wardCode)
    return {
      addressLine: facility?.addressLine ?? '',
      provinceCode: province?.code ?? '',
      wardCode: ward?.code ?? '',
    }
  })
  const province = provinceByCode(address.provinceCode)
  const ward = wardByCode(address.provinceCode, address.wardCode)
  const provinceError = fields.provinceCode || fields.province
  const wardError = fields.wardCode || fields.ward
  function selectPlace(selection: FacilityPlaceSelection) {
    setAddress({ ...selection.address })
    const point = { latitude: selection.latitude, longitude: selection.longitude }
    onLocationChange(point)
    setFocusLocation(point)
  }
  function field(
    name: keyof FacilityInput,
    label: string,
    max: number,
    required = true,
    type = 'text',
  ) {
    return (
      <label className="flex flex-col justify-start min-w-0 gap-2 text-sm font-medium self-start w-full">
        <span>
          {label}
          {required ? (
            <span className="ml-1 font-bold text-red-500">*</span>
          ) : (
            <span className="font-normal text-slate-400"> (tùy chọn)</span>
          )}
        </span>
        <Input
          type={type}
          name={name === 'name' ? nameField : name}
          defaultValue={String(facility?.[name] ?? (name === 'timezone' ? 'Asia/Ho_Chi_Minh' : ''))}
          maxLength={max}
          required={required}
          disabled={busy}
          aria-invalid={!!fields[name]}
        />
        {fields[name] && <span className="text-xs text-red-700">{fields[name]}</span>}
      </label>
    )
  }
  return (
    <>
      {field('name', 'Tên cơ sở', 180, true)}
      {field('phone', 'Số điện thoại cơ sở', 30, true)}
      {field('contactEmail', 'Thư điện tử liên hệ cơ sở', 254, false, 'email')}
      <AddressAutocomplete
        value={address.addressLine}
        onChange={(addressLine) => setAddress((current) => ({ ...current, addressLine }))}
        onSelect={selectPlace}
        disabled={busy || frozen}
        error={fields.addressLine}
      />
      <label className="flex flex-col justify-start min-w-0 gap-2 text-sm font-medium self-start w-full">
        <span>
          Tỉnh / thành phố <span className="ml-1 font-bold text-red-500">*</span>
        </span>
        <select
          name="provinceCode"
          value={address.provinceCode}
          onChange={(event) =>
            setAddress((current) => ({
              ...current,
              provinceCode: event.target.value,
              wardCode: '',
            }))
          }
          className="h-11 w-full min-w-0 rounded-xl border px-3.5"
          required
          disabled={busy || frozen}
          aria-invalid={!!provinceError}
        >
          <option value="">Chọn tỉnh / thành phố</option>
          {provinces.map((item) => (
            <option value={item.code} key={item.code}>
              {item.name}
            </option>
          ))}
        </select>
        <input
          type="hidden"
          name="province"
          value={frozen ? (facility?.province ?? '') : (province?.name ?? '')}
        />
        {provinceError && <span className="text-xs text-red-700">{provinceError}</span>}
      </label>
      <label className="flex flex-col justify-start min-w-0 gap-2 text-sm font-medium self-start w-full">
        <span>
          Xã / phường <span className="ml-1 font-bold text-red-500">*</span>
        </span>
        <select
          name="wardCode"
          value={address.wardCode}
          onChange={(event) =>
            setAddress((current) => ({ ...current, wardCode: event.target.value }))
          }
          className="h-11 w-full min-w-0 rounded-xl border px-3.5"
          required
          disabled={!province || busy || frozen}
          aria-invalid={!!wardError}
        >
          <option value="">{province ? 'Chọn xã / phường' : 'Chọn tỉnh / thành phố trước'}</option>
          {province?.wards.map((item) => (
            <option value={item.code} key={item.code}>
              {item.name}
            </option>
          ))}
        </select>
        <input
          type="hidden"
          name="ward"
          value={frozen ? (facility?.ward ?? '') : (ward?.name ?? '')}
        />
        {wardError && <span className="text-xs text-red-700">{wardError}</span>}
      </label>
      {facility && !frozen && (!province || !ward) && (
        <p role="status" className="text-xs text-amber-800 md:col-span-2">
          Địa chỉ đã lưu: {facility.ward}, {facility.province}. Chọn lại tỉnh / thành phố và xã /
          phường theo danh sách hiện hành trước khi lưu.
        </p>
      )}
      {frozen && (
        <>
          <input type="hidden" name="addressLine" value={facility?.addressLine ?? ''} />
          <input type="hidden" name="provinceCode" value={facility?.provinceCode ?? ''} />
          <input type="hidden" name="wardCode" value={facility?.wardCode ?? ''} />
        </>
      )}
      <input type="hidden" name="timezone" value={facility?.timezone || 'Asia/Ho_Chi_Minh'} />
      <label className="flex flex-col justify-start min-w-0 gap-2 text-sm font-medium self-start w-full">
        <span>
          Tiện ích, phân cách bằng dấu phẩy{' '}
          <span className="font-normal text-slate-400">(tùy chọn)</span>
        </span>
        <Input
          name="amenities"
          defaultValue={facility?.amenities.join(', ')}
          maxLength={1200}
          disabled={busy}
          placeholder="Ví dụ: Wi-Fi, Bãi đỗ xe, Phòng thay đồ, Nước uống, Đèn chiếu sáng"
        />
      </label>
      <label className="flex flex-col justify-start min-w-0 gap-2 text-sm font-medium md:col-span-2">
        <span>
          Mô tả giới thiệu <span className="font-normal text-slate-400">(tùy chọn)</span>
        </span>
        <textarea
          className="min-h-28 rounded-2xl border border-slate-200/90 p-3"
          name="description"
          defaultValue={facility?.description}
          maxLength={5000}
          disabled={busy}
          placeholder="Giới thiệu về cơ sở, không gian sân bãi, quy định đón khách..."
        />
      </label>
      <LocationPicker
        value={location}
        focusLocation={focusLocation}
        onChange={onLocationChange}
        disabled={busy || frozen}
      />
      {(fields.latitude || fields.longitude || fields.locationComplete) && (
        <p role="alert" className="text-sm text-red-700 md:col-span-2">
          {fields.latitude || fields.longitude || fields.locationComplete}
        </p>
      )}
    </>
  )
}

export { facilityFrom } from './facility-payload'
