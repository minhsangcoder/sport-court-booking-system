'use client'
import { PageBanner, bannerGhost } from '@/components/page-banner'
import { ArrowRight, Key } from 'lucide-react'
import Link from 'next/link'

export function ProfileHero() {
  return (
    <PageBanner
      eyebrow="Hồ sơ tài khoản SportHub"
      title="Cài đặt hồ sơ cá nhân"
      actions={
        <Link href="/customer/profile/contact" className={bannerGhost}>
          <Key className="size-4" />
          Bảo mật & Liên hệ
          <ArrowRight className="size-4" />
        </Link>
      }
    />
  )
}
