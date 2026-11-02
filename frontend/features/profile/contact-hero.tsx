'use client'
import { PageBanner } from '@/components/page-banner'
import { bannerLink } from '@/features/transfers/transfer-styles'
import Link from 'next/link'

export function ContactHero() {
  return (
    <PageBanner
      eyebrow={
        <Link href="/customer/profile" className={bannerLink}>
          Hồ sơ cá nhân
        </Link>
      }
      title="Bảo mật & Thông tin liên hệ"
    />
  )
}
