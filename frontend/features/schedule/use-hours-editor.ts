'use client'
import { confirmDialog } from '@/components/confirm-dialog'
import { readImpact, type ConfigurationImpact } from '@/features/integration/configuration-impact'
import {
  Interval,
  TimeRange,
  WeekdayException,
  parseInitialSchedule,
} from '@/features/schedule/hours-model'
import { weekdays } from '@/features/schedule/types'
import { api } from '@/lib/api'
import { useState } from 'react'
export function useHoursEditor({
  facilityId,
  courtId,
  initial,
  reload,
  onSaved,
}: {
  facilityId: string
  courtId: string
  initial: Interval[]
  reload: () => void
  onSaved: (message: string) => void
}) {
  const parsed = parseInitialSchedule(initial)
  const [impact, setImpact] = useState<ConfigurationImpact | null>(null)
  const [ranges, setRanges] = useState<TimeRange[]>(parsed.defaultRanges)
  const [weekdayExceptions, setWeekdayExceptions] = useState<WeekdayException[]>(
    parsed.weekdayExceptions,
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  function updateRange(index: number, key: keyof TimeRange, value: string) {
    setRanges(ranges.map((r, i) => (i === index ? { ...r, [key]: value } : r)))
  }
  function addRange() {
    if (ranges.length >= 2) return
    const firstEnd = ranges[0]?.closesAt || '12:00'
    const newStart = firstEnd < '13:00' ? '13:00' : firstEnd
    const newEnd = '22:00' > newStart ? '22:00' : '23:00'
    setRanges([...ranges, { opensAt: newStart, closesAt: newEnd }])
  }
  function removeRange(index: number) {
    setRanges(ranges.filter((_, i) => i !== index))
  }

  function addWeekdayException() {
    const usedDays = new Set(weekdayExceptions.map((e) => e.dayOfWeek))
    const preference = [6, 7, 5, 1, 2, 3, 4]
    const nextDay =
      preference.find((d) => !usedDays.has(d)) ||
      [1, 2, 3, 4, 5, 6, 7].find((d) => !usedDays.has(d))
    if (!nextDay) return
    setWeekdayExceptions([
      ...weekdayExceptions,
      { dayOfWeek: nextDay, closed: false, ranges: ranges.map((r) => ({ ...r })) },
    ])
  }
  function removeWeekdayException(index: number) {
    setWeekdayExceptions(weekdayExceptions.filter((_, i) => i !== index))
  }
  function updateExceptionDay(index: number, newDay: number) {
    setWeekdayExceptions(
      weekdayExceptions.map((e, i) => (i === index ? { ...e, dayOfWeek: newDay } : e)),
    )
  }
  function updateExceptionClosed(index: number, closed: boolean) {
    setWeekdayExceptions(weekdayExceptions.map((e, i) => (i === index ? { ...e, closed } : e)))
  }
  function updateExceptionRange(
    excIndex: number,
    rangeIndex: number,
    key: keyof TimeRange,
    val: string,
  ) {
    setWeekdayExceptions(
      weekdayExceptions.map((e, i) => {
        if (i !== excIndex) return e
        const updatedRanges = e.ranges.map((r, ri) =>
          ri === rangeIndex ? { ...r, [key]: val } : r,
        )
        return { ...e, ranges: updatedRanges }
      }),
    )
  }
  function addExceptionRange(excIndex: number) {
    setWeekdayExceptions(
      weekdayExceptions.map((e, i) => {
        if (i !== excIndex || e.ranges.length >= 2) return e
        const firstEnd = e.ranges[0]?.closesAt || '12:00'
        const newStart = firstEnd < '13:00' ? '13:00' : firstEnd
        const newEnd = '22:00' > newStart ? '22:00' : '23:00'
        return { ...e, ranges: [...e.ranges, { opensAt: newStart, closesAt: newEnd }] }
      }),
    )
  }
  function removeExceptionRange(excIndex: number, rangeIndex: number) {
    setWeekdayExceptions(
      weekdayExceptions.map((e, i) => {
        if (i !== excIndex) return e
        return { ...e, ranges: e.ranges.filter((_, ri) => ri !== rangeIndex) }
      }),
    )
  }

  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    onSaved('')
    setImpact(null)
    if (ranges.length === 0) {
      setError('Vui lòng thiết lập ít nhất một khoảng thời gian mở cửa chung.')
      setBusy(false)
      return
    }
    const toMinutes = (t: string) => {
      const [h, m] = t.split(':').map(Number)
      return h * 60 + (m || 0)
    }

    for (let i = 0; i < ranges.length; i++) {
      const r = ranges[i]
      if (!r.opensAt || !r.closesAt) {
        setError(`Giờ mở cửa chung - Khoảng ${i + 1}: Vui lòng nhập đầy đủ giờ.`)
        setBusy(false)
        return
      }
      if (r.opensAt >= r.closesAt) {
        setError(
          `Giờ mở cửa chung - Khoảng ${i + 1}: Giờ mở cửa (${r.opensAt}) phải trước giờ đóng cửa (${r.closesAt}).`,
        )
        setBusy(false)
        return
      }
      if ((toMinutes(r.closesAt) - toMinutes(r.opensAt)) % 60 !== 0) {
        setError(`Giờ mở cửa chung - Khoảng ${i + 1}: Thời lượng phải là bội số của 60 phút.`)
        setBusy(false)
        return
      }
    }
    if (ranges.length === 2) {
      const [r1, r2] = ranges
      if (r1.opensAt < r2.closesAt && r2.opensAt < r1.closesAt) {
        setError('Hai khoảng thời gian mở cửa chung không được trùng lặp nhau.')
        setBusy(false)
        return
      }
    }

    for (const exc of weekdayExceptions) {
      const dayName = weekdays[exc.dayOfWeek - 1]
      if (!exc.closed) {
        if (exc.ranges.length === 0) {
          setError(
            `Ngoại lệ ${dayName}: Vui lòng thiết lập ít nhất một khoảng giờ hoặc chọn Đóng cửa cả ngày.`,
          )
          setBusy(false)
          return
        }
        for (let i = 0; i < exc.ranges.length; i++) {
          const r = exc.ranges[i]
          if (!r.opensAt || !r.closesAt) {
            setError(`Ngoại lệ ${dayName} - Khoảng ${i + 1}: Vui lòng nhập đầy đủ giờ.`)
            setBusy(false)
            return
          }
          if (r.opensAt >= r.closesAt) {
            setError(
              `Ngoại lệ ${dayName} - Khoảng ${i + 1}: Giờ mở cửa (${r.opensAt}) phải trước giờ đóng cửa (${r.closesAt}).`,
            )
            setBusy(false)
            return
          }
          if ((toMinutes(r.closesAt) - toMinutes(r.opensAt)) % 60 !== 0) {
            setError(
              `Ngoại lệ ${dayName} - Khoảng ${i + 1}: Thời lượng phải là bội số của 60 phút.`,
            )
            setBusy(false)
            return
          }
        }
        if (exc.ranges.length === 2) {
          const [r1, r2] = exc.ranges
          if (r1.opensAt < r2.closesAt && r2.opensAt < r1.closesAt) {
            setError(`Ngoại lệ ${dayName}: Hai khoảng thời gian mở cửa không được trùng lặp nhau.`)
            setBusy(false)
            return
          }
        }
      }
    }

    const intervals: Interval[] = []
    for (let day = 1; day <= 7; day++) {
      const exc = weekdayExceptions.find((e) => e.dayOfWeek === day)
      if (exc) {
        if (!exc.closed) {
          for (const r of exc.ranges) {
            intervals.push({
              dayOfWeek: day,
              opensAt: r.opensAt,
              closesAt: r.closesAt,
              slotMinutes: 60,
            })
          }
        }
      } else {
        for (const r of ranges) {
          intervals.push({
            dayOfWeek: day,
            opensAt: r.opensAt,
            closesAt: r.closesAt,
            slotMinutes: 60,
          })
        }
      }
    }

    try {
      await api(`/schedules/facilities/${facilityId}/hours`, {
        method: 'PUT',
        body: JSON.stringify({ courtId: courtId || null, intervals }),
      })
      onSaved('Đã lưu lịch mở cửa và các ngoại lệ theo thứ thành công.')
      reload()
    } catch (err) {
      setImpact(readImpact(err))
      setError(err instanceof Error ? err.message : 'Không thể lưu lịch.')
    } finally {
      setBusy(false)
    }
  }

  async function resetToFacility() {
    if (!courtId) return
    if (!(await confirmDialog('Kế thừa lịch mở cửa của toàn cơ sở cho sân này?'))) return
    setBusy(true)
    setError('')
    try {
      await api(`/schedules/facilities/${facilityId}/hours`, {
        method: 'PUT',
        body: JSON.stringify({ courtId, intervals: [] }),
      })
      onSaved('Đã chuyển sang dùng lịch cơ sở.')
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Không thể đặt lại lịch.')
    } finally {
      setBusy(false)
    }
  }

  return {
    facilityId,
    courtId,
    initial,
    reload,
    onSaved,
    parsed,
    impact,
    setImpact,
    ranges,
    setRanges,
    weekdayExceptions,
    setWeekdayExceptions,
    busy,
    setBusy,
    error,
    setError,
    updateRange,
    addRange,
    removeRange,
    addWeekdayException,
    removeWeekdayException,
    updateExceptionDay,
    updateExceptionClosed,
    updateExceptionRange,
    addExceptionRange,
    removeExceptionRange,
    save,
    resetToFacility,
  }
}
