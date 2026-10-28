import data from './vietnam-administrative-divisions.json'

export type AdministrativeProvince = (typeof data.provinces)[number]
export type AdministrativeWard = AdministrativeProvince['wards'][number]

export const provinces: AdministrativeProvince[] = data.provinces

function normalized(value: string): string {
  return value
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLocaleLowerCase('vi')
    .replace(/đ/g, 'd')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

function provinceName(value: string): string {
  return normalized(value)
    .replace(/^(tinh|thanh pho|tp|province|city)\s+/, '')
    .replace(/\s+(province|city)$/, '')
    .replace(/\s/g, '')
}

function wardName(value: string): string {
  return normalized(value)
    .replace(/^(phuong|xa|dac khu|thi tran|ward|commune)\s+/, '')
    .replace(/\s/g, '')
}

export function provinceByCode(
  code: string | null | undefined,
): AdministrativeProvince | undefined {
  return provinces.find((province) => province.code === code)
}

export function provinceByName(
  name: string | null | undefined,
): AdministrativeProvince | undefined {
  if (!name) return undefined
  const value = provinceName(name)
  if (!value) return undefined
  return provinces.find((province) => provinceName(province.name) === value)
}

export function wardByCode(
  provinceCode: string | null | undefined,
  wardCode: string | null | undefined,
): AdministrativeWard | undefined {
  return provinceByCode(provinceCode)?.wards.find((ward) => ward.code === wardCode)
}

export function wardByName(
  provinceCode: string | null | undefined,
  name: string | null | undefined,
): AdministrativeWard | undefined {
  if (!name) return undefined
  const wards = provinceByCode(provinceCode)?.wards ?? []
  const exactName = name.trim().normalize('NFC').toLocaleLowerCase('vi')
  const exactMatches = wards.filter(
    (ward) => ward.name.normalize('NFC').toLocaleLowerCase('vi') === exactName,
  )
  if (exactMatches.length === 1) return exactMatches[0]
  const value = wardName(name)
  if (!value) return undefined
  const matches = wards.filter((ward) => wardName(ward.name) === value)
  return matches.length === 1 ? matches[0] : undefined
}

export function placeAdministrativeCodes(
  components: google.maps.places.AddressComponent[] | undefined,
): { provinceCode: string; wardCode: string } {
  const component = (type: string) =>
    components?.find((item) => item.types.includes(type))?.longText ?? ''
  const province = provinceByName(component('administrative_area_level_1'))
  if (!province) return { provinceCode: '', wardCode: '' }

  const possibleWardNames = [
    component('administrative_area_level_2'),
    component('administrative_area_level_3'),
    component('sublocality_level_1'),
    component('sublocality_level_2'),
    component('sublocality'),
    component('locality'),
  ].filter(Boolean)
  const ward = possibleWardNames
    .map((name) => wardByName(province.code, name))
    .find((match): match is AdministrativeWard => Boolean(match))

  return { provinceCode: province.code, wardCode: ward?.code ?? '' }
}
