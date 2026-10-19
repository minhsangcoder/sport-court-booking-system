import { importLibrary, setOptions } from '@googlemaps/js-api-loader'
import { placeAdministrativeCodes } from './administrative-divisions'

export type GoogleMapsLibraries = {
  maps: google.maps.MapsLibrary
  marker: google.maps.MarkerLibrary
  places: google.maps.PlacesLibrary
}

export type FacilityAddress = {
  addressLine: string
  provinceCode: string
  wardCode: string
}

export function addressFromComponents(
  components: google.maps.places.AddressComponent[] | undefined,
): FacilityAddress {
  const component = (type: string) => components?.find(item => item.types.includes(type))?.longText ?? ''
  const streetAddress = [
    component('subpremise'),
    component('premise'),
    component('street_number'),
    component('route'),
  ].filter(Boolean).join(', ')
  return {
    addressLine: streetAddress,
    ...placeAdministrativeCodes(components),
  }
}

let librariesPromise: Promise<GoogleMapsLibraries> | null = null

export function loadGoogleMaps(): Promise<GoogleMapsLibraries> {
  if (!librariesPromise) {
    const key = process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY
    if (!key) {
      return Promise.reject(new Error('Thiếu NEXT_PUBLIC_GOOGLE_MAPS_API_KEY. Hãy cấu hình API key Google Maps trong môi trường frontend.'))
    }

    setOptions({ key, v: 'weekly', language: 'vi', region: 'VN' })
    librariesPromise = Promise.all([
      importLibrary('maps') as Promise<google.maps.MapsLibrary>,
      importLibrary('marker') as Promise<google.maps.MarkerLibrary>,
      importLibrary('places') as Promise<google.maps.PlacesLibrary>,
    ]).then(([maps, marker, places]) => ({ maps, marker, places }))
      .catch(error => {
        librariesPromise = null
        throw error
      })
  }
  return librariesPromise
}
