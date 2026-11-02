'use client'
import { bannerLink } from '@/features/transfers/transfer-styles'
import { BannerPage } from '@/components/page-banner'
import { useState } from 'react'
import Link from 'next/link'
import { RequireAuth } from '@/features/auth/require-auth'
import { Panel } from '@/features/facility/owner-ui'
import { BankDestinationForm } from '@/features/payments/bank-destination-form'
import { AvatarUploader } from '@/features/profile/avatar-uploader'
import { useAuth } from '@/lib/auth-context'

function Settings() {
  const [revision, setRevision] = useState('')
  return (
    <BannerPage
      eyebrow={
        <Link className={bannerLink} href="/customer/profile">
          Hồ sơ cá nhân
        </Link>
      }
      title="Ảnh đại diện và tài khoản nhận tiền"
      className="space-y-6"
      width="max-w-3xl"
    >
      <Panel title="Ảnh đại diện">
        <AvatarUploader />
      </Panel>
      <BankDestinationForm value={revision} onChange={setRevision} summary />
    </BannerPage>
  )
}
export default function BankSettingsPage() {
  const { user } = useAuth()
  return (
    <RequireAuth roles={['CUSTOMER']}>
      <Settings key={user?.id} />
    </RequireAuth>
  )
}
