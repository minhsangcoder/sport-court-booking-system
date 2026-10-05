export type DiscoveryResult = {
  facility: {
    id: string
    name: string
    addressLine: string
    province: string
    district: string
    ward: string
    timezone: string
    amenities: string[]
    latitude: number | null
    longitude: number | null
  }
  courtId: string
  courtName: string
  sportCategoryId: string
  sportName: string
  date: string
  fromPrice: number
  currency: string
  availableSlots: number
  firstStartsAt: string
  firstEndsAt: string
  distanceKm: number | null
}
