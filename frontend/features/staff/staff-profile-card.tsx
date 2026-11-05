'use client'
import { confirmDanger, confirmDialog } from '@/components/confirm-dialog'
import { Button } from '@/components/ui/button'
import { Status } from '@/features/integration/shared'
import { StaffPasswordForm } from '@/features/staff/staff-password-form'
import { StaffPermissionEditor } from '@/features/staff/staff-permission-editor'
import { useStaffProfile } from '@/features/staff/use-staff-profile'
import { businessApi, type StaffProfile } from '@/lib/business-api'
import { Check, KeyRound } from 'lucide-react'
export function StaffProfileCard({
  profile: p,
  facility,
  catalog,
  reload,
}: {
  profile: StaffProfile
  facility: string
  catalog: string[]
  reload: () => void
}) {
  const model = useStaffProfile({
    profile: p,
    facility,
    catalog,
    reload,
  })
  const { setResettingPassword, resettingPassword, a, version, passwordSuccess } = model
  return (
    <div className="p-5 transition-all hover:border-slate-300 rounded-3xl border border-slate-200/90 bg-white shadow-xl shadow-slate-900/5 dark:border-slate-800 dark:bg-slate-900 home-rise">
      <div className="flex flex-wrap items-start justify-between gap-4 border-b border-slate-100 pb-4 dark:border-slate-800">
        <div>
          <div className="flex items-center gap-2.5">
            <h4 className="text-base font-bold text-slate-900 dark:text-white">{p.fullName}</h4>
            <span
              className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                p.active
                  ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                  : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400'
              }`}
            >
              {p.active ? 'Đang làm việc' : 'Ngừng làm việc'}
            </span>
            <span className="rounded-full bg-blue-50 px-2.5 py-0.5 text-xs font-semibold text-blue-700 dark:bg-blue-950 dark:text-blue-300">
              Nhân viên
            </span>
          </div>
          <p className="mt-1 text-xs text-slate-500">
            {p.email && (
              <span>
                Thư điện tử:{' '}
                <strong className="text-slate-700 dark:text-slate-300">{p.email}</strong>
              </span>
            )}
            {p.email && p.phone && <span className="mx-1.5">·</span>}
            {p.phone && (
              <span>
                SĐT: <strong className="text-slate-700 dark:text-slate-300">{p.phone}</strong>
              </span>
            )}
          </p>
        </div>

        {/* Action Buttons */}
        <div className="flex flex-wrap items-center gap-2">
          {p.linked && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setResettingPassword(!resettingPassword)}
              className="gap-1.5 text-xs"
            >
              <KeyRound size={14} />
              <span>{resettingPassword ? 'Hủy đổi MK' : 'Đặt lại mật khẩu'}</span>
            </Button>
          )}

          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={a.busy}
            onClick={async () => {
              if (
                await confirmDialog(
                  p.active
                    ? 'Xác nhận ngừng làm việc cho nhân viên này?'
                    : 'Kích hoạt lại nhân viên này?',
                )
              ) {
                void a.run(
                  () =>
                    businessApi.profile(
                      facility,
                      {
                        fullName: p.fullName,
                        email: p.email,
                        phone: p.phone,
                        active: !p.active,
                        expectedVersion: p.version,
                      },
                      p.id,
                    ),
                  reload,
                )
              }
            }}
            className="text-xs"
          >
            {p.active ? 'Ngừng làm việc' : 'Kích hoạt lại'}
          </Button>

          {p.linked && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={a.busy}
              onClick={async () => {
                if (await confirmDanger('Thu hồi liên kết quyền của nhân viên này tại cơ sở?')) {
                  void a.run(
                    () =>
                      import('@/lib/api').then(({ api }) =>
                        api(`/staff-bindings/facilities/${facility}/${p.bindingId}`, {
                          method: 'DELETE',
                        }),
                      ),
                    reload,
                  )
                }
              }}
              className="text-xs text-red-600 hover:bg-red-50 hover:text-red-700 dark:text-red-400"
            >
              Thu hồi liên kết
            </Button>
          )}
        </div>
      </div>

      <Status error={a.error || version.error} />

      {passwordSuccess && (
        <div className="mt-3 flex items-center gap-2 rounded-lg bg-emerald-50 p-2.5 text-xs font-semibold text-emerald-800">
          <Check size={14} className="text-emerald-600" />
          <span>{passwordSuccess}</span>
        </div>
      )}

      {/* Inline Reset Password Form */}
      {resettingPassword && <StaffPasswordForm model={model} />}

      {/* Permissions Editor */}
      <StaffPermissionEditor model={model} />

      {a.message && (
        <p role="status" className="mt-2 text-xs font-semibold text-emerald-700">
          {a.message}
        </p>
      )}
    </div>
  )
}
