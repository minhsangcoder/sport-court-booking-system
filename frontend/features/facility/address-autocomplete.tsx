'use client'

import { Input } from '@/components/ui/input'
import { useEffect, useId, useRef, useState } from 'react'
import { addressFromComponents, loadGoogleMaps, type FacilityAddress } from './google-maps'

export type FacilityPlaceSelection = {
  latitude: number
  longitude: number
  address: FacilityAddress
}

export function AddressAutocomplete({
  value,
  onChange,
  onSelect,
  disabled,
  error,
}: {
  value: string
  onChange: (value: string) => void
  onSelect: (selection: FacilityPlaceSelection) => void
  disabled: boolean
  error?: string
}) {
  const id = useId()
  const [places, setPlaces] = useState<google.maps.PlacesLibrary | null>(null)
  const [suggestions, setSuggestions] = useState<google.maps.places.PlacePrediction[]>([])
  const [suggestionsQuery, setSuggestionsQuery] = useState('')
  const [activeSuggestion, setActiveSuggestion] = useState(-1)
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')
  const sessionToken = useRef<google.maps.places.AutocompleteSessionToken | null>(null)
  const requestSequence = useRef(0)
  const selectedValue = useRef<string | null>(null)

  useEffect(() => {
    let active = true
    void loadGoogleMaps()
      .then((libraries) => {
        if (active) setPlaces(libraries.places)
      })
      .catch(() => {
        // Google Maps is optional - fallback to manual input or Leaflet
      })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    const query = value.trim()
    const sequence = ++requestSequence.current
    if (query === selectedValue.current) {
      selectedValue.current = null
      return
    }
    if (!places || query.length < 3 || disabled) {
      return
    }

    const timer = window.setTimeout(() => {
      setLoading(true)
      sessionToken.current ??= new places.AutocompleteSessionToken()
      void places.AutocompleteSuggestion.fetchAutocompleteSuggestions({
        input: query,
        includedRegionCodes: ['vn'],
        language: 'vi',
        region: 'vn',
        sessionToken: sessionToken.current,
      })
        .then((response) => {
          if (sequence !== requestSequence.current) return
          setSuggestions(
            response.suggestions.flatMap((suggestion) =>
              suggestion.placePrediction ? [suggestion.placePrediction] : [],
            ),
          )
          setSuggestionsQuery(query)
          setActiveSuggestion(-1)
          setMessage('')
        })
        .catch(() => {
          if (sequence === requestSequence.current) {
            setSuggestions([])
            setMessage('Không thể tìm gợi ý địa chỉ. Bạn vẫn có thể nhập địa chỉ thủ công.')
          }
        })
        .finally(() => {
          if (sequence === requestSequence.current) setLoading(false)
        })
    }, 250)

    return () => window.clearTimeout(timer)
  }, [value, places, disabled])

  async function selectPrediction(prediction: google.maps.places.PlacePrediction) {
    if (loading || disabled) return
    const sequence = ++requestSequence.current
    setLoading(true)
    setMessage('')
    try {
      const place = prediction.toPlace()
      await place.fetchFields({ fields: ['addressComponents', 'location'] })
      if (sequence !== requestSequence.current) return
      if (!place.location) {
        setMessage('Gợi ý này chưa có tọa độ bản đồ. Hãy chọn một địa chỉ khác.')
        return
      }
      const address = addressFromComponents(place.addressComponents)
      selectedValue.current = address.addressLine
      onSelect({
        latitude: Number(place.location.lat().toFixed(7)),
        longitude: Number(place.location.lng().toFixed(7)),
        address,
      })
      sessionToken.current = null
      setSuggestions([])
      setSuggestionsQuery('')
      setActiveSuggestion(-1)
    } catch {
      if (sequence === requestSequence.current) {
        setMessage('Không thể tải chi tiết địa chỉ. Bạn vẫn có thể nhập địa chỉ thủ công.')
      }
    } finally {
      if (sequence === requestSequence.current) setLoading(false)
    }
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' && suggestions.length) {
      event.preventDefault()
      setActiveSuggestion((index) => (index + 1) % suggestions.length)
    } else if (event.key === 'ArrowUp' && suggestions.length) {
      event.preventDefault()
      setActiveSuggestion((index) => (index <= 0 ? suggestions.length - 1 : index - 1))
    } else if (event.key === 'Enter' && activeSuggestion >= 0) {
      event.preventDefault()
      void selectPrediction(suggestions[activeSuggestion])
    } else if (event.key === 'Escape') {
      setSuggestions([])
      setActiveSuggestion(-1)
    }
  }

  return (
    <div className="relative flex flex-col justify-start min-w-0 gap-2 text-sm font-medium self-start w-full">
      <label htmlFor={`${id}-input`}>
        Địa chỉ <span className="ml-1 font-bold text-red-500">*</span>
      </label>
      <Input
        id={`${id}-input`}
        name="addressLine"
        value={value}
        onChange={(event) => {
          selectedValue.current = null
          setSuggestions([])
          setSuggestionsQuery('')
          setActiveSuggestion(-1)
          setLoading(false)
          onChange(event.target.value)
        }}
        onKeyDown={handleKeyDown}
        maxLength={500}
        required
        disabled={disabled}
        autoComplete="off"
        placeholder="Số nhà, tên đường hoặc địa chỉ chi tiết"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={suggestionsQuery === value.trim() && suggestions.length > 0}
        aria-controls={`${id}-suggestions`}
        aria-activedescendant={
          activeSuggestion >= 0 ? `${id}-suggestion-${activeSuggestion}` : undefined
        }
        aria-invalid={Boolean(error)}
      />
      <span className="text-xs font-normal text-slate-500">
        Nhập số nhà, đường và địa chỉ chi tiết. Chọn tỉnh / thành phố và xã / phường ở các danh sách
        bên dưới.
      </span>
      {loading && !disabled && value.trim().length >= 3 && (
        <span role="status" className="text-xs text-slate-500">
          Đang tìm địa chỉ…
        </span>
      )}
      {suggestionsQuery === value.trim() && !disabled && suggestions.length > 0 && (
        <ul
          id={`${id}-suggestions`}
          role="listbox"
          className="absolute left-0 right-0 top-full z-20 mt-1 max-h-64 overflow-auto rounded-lg border bg-white py-1 shadow-lg"
        >
          {suggestions.map((prediction, index) => (
            <li
              key={prediction.placeId}
              id={`${id}-suggestion-${index}`}
              role="option"
              aria-selected={activeSuggestion === index}
            >
              <button
                type="button"
                className={`w-full px-3 py-2 text-left text-sm ${activeSuggestion === index ? 'bg-emerald-50' : 'hover:bg-slate-50'}`}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => void selectPrediction(prediction)}
                disabled={loading || disabled}
              >
                <span className="block font-medium">
                  {prediction.mainText?.text ?? prediction.text.text}
                </span>
                {prediction.secondaryText?.text && (
                  <span className="mt-0.5 block text-xs text-slate-500">
                    {prediction.secondaryText.text}
                  </span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
      {error && <span className="text-xs text-red-700">{error}</span>}
      {message && (
        <span role="status" className="text-xs text-amber-800">
          {message}
        </span>
      )}
    </div>
  )
}
