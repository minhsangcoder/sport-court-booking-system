'use client'
import { BannerPage } from '@/components/page-banner'
import Image from 'next/image'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { RequireAuth } from '@/features/auth/require-auth'
import { useAuth } from '@/lib/auth-context'
import { api, ApiError } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { type OwnerApplication } from './types'
import { LegalFields } from './legal-fields'
import { legalFrom } from './legal-payload'
import { FacilityFields } from '@/features/facility/facility-fields'
import { facilityFrom } from '@/features/facility/facility-payload'
import type { FacilityLocation } from '@/features/facility/location-picker'

export function OwnerApplyPage() {
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Create />
    </RequireAuth>
  )
}

function Create() {
  const { user } = useAuth()
  const router = useRouter()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [location, setLocation] = useState<FacilityLocation>(null)
  const [primaryPreview, setPrimaryPreview] = useState<string | null>(null)
  const [galleryPreviews, setGalleryPreviews] = useState<string[]>([])

  if (user?.roles.includes('OWNER'))
    return (
      <BannerPage title="Bạn đã có quyền chủ cơ sở" width="max-w-3xl">
        <Link
          href="/owner/facilities"
          className="mt-5 block font-semibold text-emerald-700 hover:underline"
        >
          Quản lý cơ sở của tôi
        </Link>
      </BannerPage>
    )

  function handlePrimaryChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) {
      setPrimaryPreview(URL.createObjectURL(file))
    } else {
      setPrimaryPreview(null)
    }
  }

  function handleGalleryChange(e: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files || [])
    setGalleryPreviews(files.map((f) => URL.createObjectURL(f)))
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    const f = new FormData(e.currentTarget)
    if (!location) {
      setError('Vui lòng chọn vị trí cơ sở trên bản đồ.')
      setBusy(false)
      return
    }

    try {
      const application = await api<OwnerApplication>('/owner-applications', {
        method: 'POST',
        body: JSON.stringify({
          legal: legalFrom(f),
          facility: facilityFrom(f, location, 'facilityName'),
        }),
      })

      // Tải ảnh chính trước để làm ảnh hiển thị mặc định trên trang tìm sân
      const primaryFile = f.get('primaryImage') as File | null
      if (primaryFile && primaryFile.size > 0) {
        const primaryData = new FormData()
        primaryData.append('file', primaryFile)
        try {
          await api(`/owner/facilities/${application.facilityId}/images`, {
            method: 'POST',
            body: primaryData,
          })
        } catch (err) {
          console.warn('Lỗi tải ảnh chính:', err)
        }
      }

      // Tải các ảnh mô tả kèm theo
      const galleryFiles = f.getAll('galleryImages') as File[]
      for (const file of galleryFiles) {
        if (file && file.size > 0) {
          const galleryData = new FormData()
          galleryData.append('file', file)
          try {
            await api(`/owner/facilities/${application.facilityId}/images`, {
              method: 'POST',
              body: galleryData,
            })
          } catch (err) {
            console.warn('Lỗi tải ảnh mô tả:', err)
          }
        }
      }

      router.push(`/owner/application/${application.id}`)
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.message +
              (err.fieldErrors.length ? ' ' + err.fieldErrors.map((f) => f.message).join('; ') : '')
          : err instanceof Error
            ? err.message
            : 'Không thể tạo hồ sơ.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <BannerPage
      eyebrow="TRỞ THÀNH ĐỐI TÁC SPORTHUB"
      title="Đăng ký trở thành chủ cơ sở"
      description="Tạo hồ sơ nháp, sau đó khai báo sân, lịch mở cửa, bảng giá và tài liệu cho cơ sở đầu tiên trước khi gửi quản trị viên thẩm định."
      width="max-w-4xl"
    >
      <Link
        href="/owner/application"
        className="mt-4 inline-block text-sm font-semibold text-emerald-700 hover:underline"
      >
        Xem hồ sơ đã đăng ký
      </Link>

      <form onSubmit={submit} className="mt-8 space-y-8">
        <fieldset className="grid min-w-0 grid-cols-1 gap-5 p-6 md:grid-cols-2 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          <legend className="px-2 text-xl font-bold">Người đại diện và thông tin pháp lý</legend>
          <LegalFields busy={busy} />
          <p className="text-xs text-slate-500 md:col-span-2">
            Giấy tờ định danh và ngân hàng chỉ dành cho người đăng ký và quản trị viên thẩm định.
            Không nhập dữ liệu thật trong bản mô phỏng.
          </p>
        </fieldset>

        <fieldset className="grid min-w-0 grid-cols-1 gap-5 p-6 md:grid-cols-2 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          <legend className="px-2 text-xl font-bold">Cơ sở đầu tiên</legend>
          <FacilityFields
            nameField="facilityName"
            location={location}
            onLocationChange={setLocation}
            busy={busy}
          />
        </fieldset>

        {/* Mục 5: Thêm ảnh cơ sở gồm 1 ảnh chính và các ảnh mô tả */}
        <fieldset className="grid min-w-0 grid-cols-1 gap-6 p-6 md:grid-cols-2 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
          <legend className="px-2 text-xl font-bold">Hình ảnh cơ sở</legend>

          {/* 1. Ảnh chính (hiển thị ở mục tìm sân) */}
          <div className="flex flex-col justify-start gap-2 text-sm font-medium self-start w-full">
            <div className="flex items-center justify-between">
              <span>
                Ảnh chính cơ sở <span className="ml-1 font-bold text-red-500">*</span>
              </span>
              <span className="rounded-full bg-emerald-50 px-2.5 py-0.5 text-xs font-semibold text-emerald-800">
                Hiển thị ở mục tìm sân
              </span>
            </div>
            <p className="text-xs text-slate-500">
              Ảnh bìa mặt tiền hoặc góc đẹp nhất của cụm sân, hiển thị nổi bật trên thẻ tìm kiếm
              (JPG/PNG, tối đa 10 MB).
            </p>
            <div className="mt-1 flex flex-col gap-3">
              <input
                type="file"
                name="primaryImage"
                accept="image/png,image/jpeg,image/webp"
                required
                disabled={busy}
                onChange={handlePrimaryChange}
                className="text-xs text-slate-600 file:mr-3 file:rounded-full file:border-0 file:bg-emerald-600 file:px-4 file:py-2 file:text-xs file:font-bold file:text-white hover:file:bg-emerald-600 cursor-pointer"
              />
              {primaryPreview && (
                <div className="relative h-44 w-full overflow-hidden rounded-xl border border-emerald-300 bg-slate-900 shadow-sm">
                  <Image
                    unoptimized
                    width={640}
                    height={176}
                    src={primaryPreview}
                    alt="Xem trước ảnh chính"
                    className="h-full w-full object-cover"
                  />
                  <span className="absolute top-2 left-2 rounded-full bg-emerald-600/90 px-2 py-0.5 text-[11px] font-bold text-white shadow-xs">
                    ★ Ảnh chính (Tìm sân)
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* 2. Các ảnh mô tả sân (tùy chọn) */}
          <div className="flex flex-col justify-start gap-2 text-sm font-medium self-start w-full">
            <div className="flex items-center justify-between">
              <span>
                Các ảnh mô tả sân <span className="font-normal text-slate-400">(tùy chọn)</span>
              </span>
              <span className="text-xs text-slate-500 font-normal">Nhiều ảnh</span>
            </div>
            <p className="text-xs text-slate-500">
              Ảnh chi tiết từng sân, khu vực nghỉ ngơi, phòng thay đồ, bãi đỗ xe hoặc bảng giá.
            </p>
            <div className="mt-1 flex flex-col gap-3">
              <input
                type="file"
                name="galleryImages"
                accept="image/png,image/jpeg,image/webp"
                multiple
                disabled={busy}
                onChange={handleGalleryChange}
                className="text-xs text-slate-600 file:mr-3 file:rounded-full file:border border-slate-200-0 file:bg-slate-100 file:px-4 file:py-2 file:text-xs file:font-bold file:text-slate-800 hover:file:bg-slate-200 cursor-pointer"
              />
              {galleryPreviews.length > 0 && (
                <div className="grid grid-cols-3 gap-2 max-h-44 overflow-y-auto rounded-xl border border-slate-200 p-2 bg-slate-50">
                  {galleryPreviews.map((src, i) => (
                    <div
                      key={i}
                      className="relative h-20 rounded-lg overflow-hidden border bg-white shadow-xs"
                    >
                      <Image
                        unoptimized
                        width={160}
                        height={80}
                        src={src}
                        alt={`Ảnh mô tả ${i + 1}`}
                        className="h-full w-full object-cover"
                      />
                      <span className="absolute bottom-1 right-1 rounded bg-black/60 px-1 text-[9px] text-white">
                        #{i + 1}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </fieldset>

        {error && (
          <p role="alert" className="text-red-700">
            {error}
          </p>
        )}
        <Button type="submit" disabled={busy} className="bg-emerald-600 text-white">
          {busy ? 'Đang tạo hồ sơ và lưu ảnh…' : 'Tạo hồ sơ và tiếp tục'}
        </Button>
      </form>
    </BannerPage>
  )
}
