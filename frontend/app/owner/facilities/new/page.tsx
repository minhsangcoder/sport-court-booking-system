import { BannerPage } from '@/components/page-banner'
import { RequireAuth } from '@/features/auth/require-auth'
import { FacilityForm } from '@/features/facility/facility-form'
export default function Page() {
  return (
    <RequireAuth roles={['OWNER']}>
      <BannerPage
        eyebrow="Không gian chủ cơ sở"
        title="Tạo cơ sở"
        description="Khai báo thông tin cơ bản, vị trí và ảnh của cơ sở."
        width="max-w-4xl"
      >
        <FacilityForm />
      </BannerPage>
    </RequireAuth>
  )
}
