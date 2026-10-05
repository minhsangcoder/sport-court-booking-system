import { RequireAuth } from '@/features/auth/require-auth'
import { FacilityForm } from '@/features/facility/facility-form'
export default function Page(){return <RequireAuth roles={['OWNER']}><div className="mx-auto max-w-4xl px-5 py-10"><h1 className="mb-7 text-3xl font-bold">Tạo cơ sở</h1><FacilityForm/></div></RequireAuth>}
